package com.TrainTracker.backend.service;

import com.TrainTracker.backend.gtfs.GtfsStop;
import com.TrainTracker.backend.gtfs.GtfsStopTime;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class GtfsStaticService {

    private static final Logger log = LoggerFactory.getLogger(GtfsStaticService.class);
    private static final DateTimeFormatter GTFS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Value("${gtfs.static.url}")
    private String gtfsStaticUrl;

    // stop_id → gare
    private volatile Map<String, GtfsStop> stopsById = new ConcurrentHashMap<>();

    // headsign normalisé → liste de trip_ids
    private volatile Map<String, List<String>> tripsByHeadsign = new ConcurrentHashMap<>();

    // trip_id → service_id
    private volatile Map<String, String> serviceByTripId = new ConcurrentHashMap<>();

    // trip_id → liste d'arrêts ordonnés
    private volatile Map<String, List<GtfsStopTime>> stopTimesByTripId = new ConcurrentHashMap<>();

    // service_id → [lun, mar, mer, jeu, ven, sam, dim]
    private volatile Map<String, boolean[]> calendarWeekdays = new ConcurrentHashMap<>();

    // service_id → [dateDebut, dateFin]
    private volatile Map<String, LocalDate[]> calendarDateRange = new ConcurrentHashMap<>();

    // service_id → dates ajoutées / supprimées
    private volatile Map<String, Set<LocalDate>> calendarAddedDates = new ConcurrentHashMap<>();
    private volatile Map<String, Set<LocalDate>> calendarRemovedDates = new ConcurrentHashMap<>();

    private volatile boolean loaded = false;

    @PostConstruct
    public void init() {
        new Thread(this::loadGtfs).start();
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void refresh() {
        new Thread(this::loadGtfs).start();
    }

    public boolean isLoaded() {
        return loaded;
    }

    // ─── Requêtes publiques ───────────────────────────────────────────────────────

    public Optional<String> findTripId(String headsign, LocalDate date) {
        String key = normalizeHeadsign(headsign);
        List<String> candidates = tripsByHeadsign.getOrDefault(key, List.of());
        return candidates.stream()
                .filter(tripId -> isServiceActive(serviceByTripId.get(tripId), date))
                .findFirst();
    }

    public List<GtfsStopTime> getStopTimes(String tripId) {
        return stopTimesByTripId.getOrDefault(tripId, List.of());
    }

    public Optional<GtfsStop> getStop(String stopId) {
        return Optional.ofNullable(stopsById.get(stopId));
    }

    public int getStopsCount() { return stopsById.size(); }
    public int getTripsCount() { return tripsByHeadsign.size(); }

    public List<String> searchHeadsigns(String query) {
        String q = query.toUpperCase();
        return tripsByHeadsign.keySet().stream()
                .filter(k -> k.contains(q))
                .sorted()
                .limit(20)
                .toList();
    }

    // ─── Chargement ──────────────────────────────────────────────────────────────

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();

    private void loadGtfs() {
        log.info("Téléchargement GTFS SNCF depuis {}...", gtfsStaticUrl);
        Path tempFile = null;
        try {
            // Téléchargement sur disque pour permettre deux passes sans tout garder en mémoire
            tempFile = Files.createTempFile("gtfs-sncf", ".zip");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(gtfsStaticUrl))
                    .timeout(java.time.Duration.ofMinutes(5))
                    .GET()
                    .build();
            HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
            log.info("GTFS téléchargé ({} Mo), parsing...", Files.size(tempFile) / 1_048_576);

            // Passe 1 : tout sauf stop_times (léger)
            Map<String, GtfsStop> newStops = new HashMap<>();
            Map<String, List<String>> newTripsByHeadsign = new HashMap<>();
            Map<String, String> newServiceByTripId = new HashMap<>();
            Map<String, boolean[]> newWeekdays = new HashMap<>();
            Map<String, LocalDate[]> newDateRange = new HashMap<>();
            Map<String, Set<LocalDate>> newAdded = new HashMap<>();
            Map<String, Set<LocalDate>> newRemoved = new HashMap<>();

            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tempFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
                    switch (entry.getName()) {
                        case "stops.txt"          -> parseStops(reader, newStops);
                        case "trips.txt"          -> parseTrips(reader, newTripsByHeadsign, newServiceByTripId);
                        case "calendar.txt"       -> parseCalendar(reader, newWeekdays, newDateRange);
                        case "calendar_dates.txt" -> parseCalendarDates(reader, newAdded, newRemoved);
                    }
                    zis.closeEntry();
                }
            }

            // Calcul des trips actifs (aujourd'hui + demain)
            LocalDate today = LocalDate.now(ZoneId.of("Europe/Paris"));
            Set<String> activeTripIds = new HashSet<>();
            for (Map.Entry<String, String> e : newServiceByTripId.entrySet()) {
                String sid = e.getValue();
                for (int i = 0; i < 2; i++) {
                    if (isServiceActiveOn(sid, today.plusDays(i), newWeekdays, newDateRange, newAdded, newRemoved)) {
                        activeTripIds.add(e.getKey());
                        break;
                    }
                }
            }

            // Passe 2 : stop_times filtrés à la lecture — on ne charge jamais les trips inactifs
            Map<String, List<GtfsStopTime>> newStopTimes = new HashMap<>();
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tempFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if ("stop_times.txt".equals(entry.getName())) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
                        parseStopTimesFiltered(reader, newStopTimes, activeTripIds);
                    }
                    zis.closeEntry();
                }
            }

            newStopTimes.values().forEach(list -> list.sort(Comparator.comparingInt(GtfsStopTime::stopSequence)));

            // Nettoyage des autres maps
            newServiceByTripId.keySet().retainAll(activeTripIds);
            newTripsByHeadsign.values().forEach(list -> list.retainAll(activeTripIds));
            newTripsByHeadsign.entrySet().removeIf(e -> e.getValue().isEmpty());
            newStops.entrySet().removeIf(e -> {
                GtfsStop s = e.getValue();
                return (s.lat() == 0.0 && s.lon() == 0.0)
                        || s.lat() < 41.0 || s.lat() > 52.0
                        || s.lon() < -6.0 || s.lon() > 10.5;
            });

            stopsById            = new ConcurrentHashMap<>(newStops);
            tripsByHeadsign      = new ConcurrentHashMap<>(newTripsByHeadsign);
            serviceByTripId      = new ConcurrentHashMap<>(newServiceByTripId);
            stopTimesByTripId    = new ConcurrentHashMap<>(newStopTimes);
            calendarWeekdays     = new ConcurrentHashMap<>(newWeekdays);
            calendarDateRange    = new ConcurrentHashMap<>(newDateRange);
            calendarAddedDates   = new ConcurrentHashMap<>(newAdded);
            calendarRemovedDates = new ConcurrentHashMap<>(newRemoved);
            loaded = true;

            log.info("GTFS chargé : {} gares, {} trips actifs (2 jours)", stopsById.size(), activeTripIds.size());

        } catch (Exception e) {
            log.error("Erreur chargement GTFS : {}", e.getMessage(), e);
        } finally {
            if (tempFile != null) try { Files.delete(tempFile); } catch (Exception ignored) {}
        }
    }

    // ─── Parsers ─────────────────────────────────────────────────────────────────

    private void parseStops(BufferedReader reader, Map<String, GtfsStop> out) throws Exception {
        Map<String, Integer> idx = parseHeader(reader.readLine());
        int iId   = idx.get("stop_id");
        int iName = idx.get("stop_name");
        int iLat  = idx.get("stop_lat");
        int iLon  = idx.get("stop_lon");

        String line;
        while ((line = reader.readLine()) != null) {
            String[] f = parseCsvLine(line);
            if (f.length <= Math.max(iId, Math.max(iName, Math.max(iLat, iLon)))) continue;
            try {
                out.put(f[iId], new GtfsStop(
                        f[iId], f[iName],
                        Double.parseDouble(f[iLat]),
                        Double.parseDouble(f[iLon])));
            } catch (NumberFormatException ignored) {}
        }
    }

    private void parseTrips(BufferedReader reader,
                            Map<String, List<String>> byHeadsign,
                            Map<String, String> serviceByTrip) throws Exception {
        Map<String, Integer> idx = parseHeader(reader.readLine());
        int iTripId    = idx.get("trip_id");
        int iServiceId = idx.get("service_id");
        int iHeadsign  = idx.getOrDefault("trip_headsign", -1);

        String line;
        while ((line = reader.readLine()) != null) {
            String[] f = parseCsvLine(line);
            if (f.length <= Math.max(iTripId, iServiceId)) continue;
            String tripId    = f[iTripId];
            String serviceId = f[iServiceId];
            String headsign  = (iHeadsign >= 0 && iHeadsign < f.length) ? f[iHeadsign] : "";

            serviceByTrip.put(tripId, serviceId);
            if (!headsign.isBlank()) {
                String key = normalizeHeadsign(headsign);
                byHeadsign.computeIfAbsent(key, k -> new ArrayList<>()).add(tripId);
            }
        }
    }

    private void parseStopTimes(BufferedReader reader,
                                Map<String, List<GtfsStopTime>> out) throws Exception {
        Map<String, Integer> idx = parseHeader(reader.readLine());
        int iTripId   = idx.get("trip_id");
        int iArrival  = idx.get("arrival_time");
        int iDepart   = idx.get("departure_time");
        int iStopId   = idx.get("stop_id");
        int iSeq      = idx.get("stop_sequence");

        String line;
        while ((line = reader.readLine()) != null) {
            String[] f = parseCsvLine(line);
            int max = Math.max(iTripId, Math.max(iArrival, Math.max(iDepart, Math.max(iStopId, iSeq))));
            if (f.length <= max) continue;
            try {
                String tripId = f[iTripId];
                out.computeIfAbsent(tripId, k -> new ArrayList<>())
                   .add(new GtfsStopTime(
                           tripId,
                           f[iStopId],
                           f[iArrival],
                           f[iDepart],
                           Integer.parseInt(f[iSeq])));
            } catch (NumberFormatException ignored) {}
        }
    }

    private void parseStopTimesFiltered(BufferedReader reader,
                                        Map<String, List<GtfsStopTime>> out,
                                        Set<String> activeTripIds) throws Exception {
        Map<String, Integer> idx = parseHeader(reader.readLine());
        int iTripId = idx.get("trip_id");
        int iArrival = idx.get("arrival_time");
        int iDepart  = idx.get("departure_time");
        int iStopId  = idx.get("stop_id");
        int iSeq     = idx.get("stop_sequence");
        int max = Math.max(iTripId, Math.max(iArrival, Math.max(iDepart, Math.max(iStopId, iSeq))));

        String line;
        while ((line = reader.readLine()) != null) {
            String[] f = parseCsvLine(line);
            if (f.length <= max) continue;
            String tripId = f[iTripId];
            if (!activeTripIds.contains(tripId)) continue;
            try {
                out.computeIfAbsent(tripId, k -> new ArrayList<>())
                   .add(new GtfsStopTime(tripId, f[iStopId], f[iArrival], f[iDepart], Integer.parseInt(f[iSeq])));
            } catch (NumberFormatException ignored) {}
        }
    }

    private void parseCalendar(BufferedReader reader,
                               Map<String, boolean[]> weekdays,
                               Map<String, LocalDate[]> dateRange) throws Exception {
        Map<String, Integer> idx = parseHeader(reader.readLine());
        String[] days = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};

        String line;
        while ((line = reader.readLine()) != null) {
            String[] f = parseCsvLine(line);
            if (f.length < 10) continue;
            String serviceId = f[idx.get("service_id")];
            boolean[] wd = new boolean[7];
            for (int i = 0; i < 7; i++) {
                Integer col = idx.get(days[i]);
                wd[i] = col != null && col < f.length && "1".equals(f[col]);
            }
            weekdays.put(serviceId, wd);
            try {
                LocalDate start = LocalDate.parse(f[idx.get("start_date")], GTFS_DATE);
                LocalDate end   = LocalDate.parse(f[idx.get("end_date")],   GTFS_DATE);
                dateRange.put(serviceId, new LocalDate[]{start, end});
            } catch (Exception ignored) {}
        }
    }

    private void parseCalendarDates(BufferedReader reader,
                                    Map<String, Set<LocalDate>> added,
                                    Map<String, Set<LocalDate>> removed) throws Exception {
        Map<String, Integer> idx = parseHeader(reader.readLine());
        int iService = idx.get("service_id");
        int iDate    = idx.get("date");
        int iType    = idx.get("exception_type");

        String line;
        while ((line = reader.readLine()) != null) {
            String[] f = parseCsvLine(line);
            if (f.length <= Math.max(iService, Math.max(iDate, iType))) continue;
            try {
                String serviceId = f[iService];
                LocalDate date   = LocalDate.parse(f[iDate], GTFS_DATE);
                if ("1".equals(f[iType])) {
                    added.computeIfAbsent(serviceId, k -> new HashSet<>()).add(date);
                } else if ("2".equals(f[iType])) {
                    removed.computeIfAbsent(serviceId, k -> new HashSet<>()).add(date);
                }
            } catch (Exception ignored) {}
        }
    }

    // ─── Utilitaires ─────────────────────────────────────────────────────────────

    private boolean isServiceActiveOn(String serviceId, LocalDate date,
            Map<String, boolean[]> weekdays, Map<String, LocalDate[]> dateRange,
            Map<String, Set<LocalDate>> added, Map<String, Set<LocalDate>> removed) {
        if (serviceId == null) return false;
        if (added.getOrDefault(serviceId, Set.of()).contains(date)) return true;
        if (removed.getOrDefault(serviceId, Set.of()).contains(date)) return false;
        boolean[] wd = weekdays.get(serviceId);
        LocalDate[] range = dateRange.get(serviceId);
        if (wd == null || range == null) return false;
        if (date.isBefore(range[0]) || date.isAfter(range[1])) return false;
        return wd[date.getDayOfWeek().getValue() - 1];
    }

    private boolean isServiceActive(String serviceId, LocalDate date) {
        if (serviceId == null) return false;

        Set<LocalDate> added   = calendarAddedDates.getOrDefault(serviceId, Set.of());
        Set<LocalDate> removed = calendarRemovedDates.getOrDefault(serviceId, Set.of());

        if (added.contains(date))   return true;
        if (removed.contains(date)) return false;

        boolean[] wd    = calendarWeekdays.get(serviceId);
        LocalDate[] range = calendarDateRange.get(serviceId);
        if (wd == null || range == null) return false;
        if (date.isBefore(range[0]) || date.isAfter(range[1])) return false;

        int dow = date.getDayOfWeek().getValue() - 1; // 0=lundi
        return wd[dow];
    }

    /** Extrait juste le numéro du train : "TGV 6201" → "6201", "Inouï 6201" → "6201" */
    private String normalizeHeadsign(String headsign) {
        return headsign.trim().replaceAll("(?i)^(tgv|ouigo|inoui|inouï|intercités|ic)\\s*", "").toUpperCase();
    }

    private Map<String, Integer> parseHeader(String line) {
        String[] fields = parseCsvLine(line);
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < fields.length; i++) {
            map.put(fields[i].trim().replace("\uFEFF", ""), i); // retire BOM éventuel
        }
        return map;
    }

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '"')            { inQuotes = !inQuotes; }
            else if (c == ',' && !inQuotes) { fields.add(current.toString()); current.setLength(0); }
            else                     { current.append(c); }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}
