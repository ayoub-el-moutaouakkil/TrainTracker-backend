package com.TrainTracker.backend.service;

import com.TrainTracker.backend.dto.TrackJourneyRequest;
import com.TrainTracker.backend.dto.TrackingResponse;
import com.TrainTracker.backend.gtfs.GtfsStop;
import com.TrainTracker.backend.gtfs.GtfsStopTime;
import com.TrainTracker.backend.model.StopInfo;
import com.TrainTracker.backend.model.TrackedJourney;
import com.TrainTracker.backend.model.TrackingStatus;
import com.TrainTracker.backend.model.TrainPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TrainTrackingService {

    private static final Logger log = LoggerFactory.getLogger(TrainTrackingService.class);

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final GtfsStaticService gtfsStatic;
    private final GtfsRtService gtfsRt;
    private final Map<String, TrackedJourney> journeys = new ConcurrentHashMap<>();

    public TrainTrackingService(GtfsStaticService gtfsStatic, GtfsRtService gtfsRt) {
        this.gtfsStatic = gtfsStatic;
        this.gtfsRt = gtfsRt;
    }

    // ─── API publique ─────────────────────────────────────────────────────────────

    public TrackingResponse startTracking(TrackJourneyRequest request) {
        if (!gtfsStatic.isLoaded()) {
            TrackedJourney loading = TrackedJourney.builder()
                    .id(UUID.randomUUID().toString())
                    .trainNumber(request.getTrainNumber())
                    .date(request.getDate())
                    .status(TrackingStatus.UNKNOWN)
                    .lastUpdated(LocalDateTime.now(PARIS))
                    .errorMessage("Données GTFS en cours de chargement, réessaie dans quelques secondes")
                    .build();
            journeys.put(loading.getId(), loading);
            return toResponse(loading);
        }

        Optional<String> tripIdOpt = gtfsStatic.findTripId(request.getTrainNumber(), request.getDate());

        if (tripIdOpt.isEmpty()) {
            TrackedJourney unknown = TrackedJourney.builder()
                    .id(UUID.randomUUID().toString())
                    .trainNumber(request.getTrainNumber())
                    .date(request.getDate())
                    .status(TrackingStatus.UNKNOWN)
                    .lastUpdated(LocalDateTime.now(PARIS))
                    .errorMessage("Train " + request.getTrainNumber() + " introuvable le " + request.getDate())
                    .build();
            journeys.put(unknown.getId(), unknown);
            return toResponse(unknown);
        }

        String tripId = tripIdOpt.get();
        Map<String, Integer> delays = gtfsRt.getDelays(tripId);
        List<StopInfo> stops = buildStops(tripId, request.getDate(), delays);

        TrackedJourney journey = TrackedJourney.builder()
                .id(UUID.randomUUID().toString())
                .trainNumber(request.getTrainNumber())
                .date(request.getDate())
                .vehicleJourneyId(tripId)
                .stops(stops)
                .status(computeStatus(stops))
                .lastUpdated(LocalDateTime.now(PARIS))
                .build();

        journeys.put(journey.getId(), journey);
        log.info("Tracking démarré : train {} trip={} ({} arrêts)",
                request.getTrainNumber(), tripId, stops.size());
        return toResponse(journey);
    }

    public Optional<TrackingResponse> getTracking(String journeyId) {
        TrackedJourney journey = journeys.get(journeyId);
        if (journey == null) return Optional.empty();
        return Optional.of(toResponse(journey));
    }

    public List<TrackingResponse> getAllTracking() {
        return journeys.values().stream().map(this::toResponse).toList();
    }

    public boolean stopTracking(String journeyId) {
        return journeys.remove(journeyId) != null;
    }

    public Optional<TrackingResponse> refresh(String journeyId) {
        TrackedJourney journey = journeys.get(journeyId);
        if (journey == null) return Optional.empty();
        refreshJourney(journey);
        return Optional.of(toResponse(journey));
    }

    // ─── Rafraîchissement automatique ────────────────────────────────────────────

    @Scheduled(fixedDelay = 300_000)
    public void cleanupStaleJourneys() {
        LocalDateTime now = LocalDateTime.now(PARIS);
        int before = journeys.size();
        journeys.entrySet().removeIf(e -> {
            TrackedJourney j = e.getValue();
            long minutesSinceUpdate = ChronoUnit.MINUTES.between(j.getLastUpdated(), now);
            return switch (j.getStatus()) {
                case ARRIVED, CANCELLED -> minutesSinceUpdate > 30;
                case UNKNOWN            -> minutesSinceUpdate > 5;
                default                 -> minutesSinceUpdate > 240;
            };
        });
        int removed = before - journeys.size();
        if (removed > 0)
            log.info("Nettoyage : {} trajet(s) supprimé(s), {} restant(s)", removed, journeys.size());
    }

    @Scheduled(fixedRateString = "${tracking.refresh.rate-ms}")
    public void refreshAll() {
        List<TrackedJourney> active = journeys.values().stream()
                .filter(j -> j.getStatus() != TrackingStatus.ARRIVED
                        && j.getStatus() != TrackingStatus.CANCELLED)
                .toList();
        if (active.isEmpty()) return;
        log.debug("Rafraîchissement de {} trajet(s)", active.size());
        active.forEach(this::refreshJourney);
    }

    // ─── Logique interne ─────────────────────────────────────────────────────────

    private void refreshJourney(TrackedJourney journey) {
        if (journey.getVehicleJourneyId() == null) return;
        Map<String, Integer> delays = gtfsRt.getDelays(journey.getVehicleJourneyId());
        List<StopInfo> stops = buildStops(journey.getVehicleJourneyId(), journey.getDate(), delays);
        journey.setStops(stops);
        journey.setStatus(computeStatus(stops));
        journey.setErrorMessage(null);
        journey.setLastUpdated(LocalDateTime.now(PARIS));
    }

    /**
     * Construit la liste des arrêts d'un trip en combinant :
     * - les horaires schedules du GTFS statique
     * - les retards en secondes du GTFS-RT
     */
    private List<StopInfo> buildStops(String tripId, LocalDate date, Map<String, Integer> delays) {
        List<GtfsStopTime> stopTimes = gtfsStatic.getStopTimes(tripId);
        List<StopInfo> result = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(PARIS);

        for (GtfsStopTime st : stopTimes) {
            Optional<GtfsStop> stopOpt = gtfsStatic.getStop(st.stopId());
            if (stopOpt.isEmpty()) continue;
            GtfsStop stop = stopOpt.get();
            // Ignorer les gares sans coordonnées valides ou hors de France
            if (stop.lat() == 0.0 && stop.lon() == 0.0) continue;
            if (stop.lat() < 41.0 || stop.lat() > 52.0 || stop.lon() < -6.0 || stop.lon() > 10.5) continue;

            LocalDateTime scheduledArr = parseGtfsTime(st.arrivalTime(), date);
            LocalDateTime scheduledDep = parseGtfsTime(st.departureTime(), date);

            int delaySec = delays.getOrDefault(st.stopId(), 0);
            LocalDateTime expectedArr = scheduledArr != null ? scheduledArr.plusSeconds(delaySec) : null;
            LocalDateTime expectedDep = scheduledDep != null ? scheduledDep.plusSeconds(delaySec) : null;

            LocalDateTime refDep = coalesce(expectedDep, scheduledDep);
            boolean departed = refDep != null && now.isAfter(refDep);

            result.add(StopInfo.builder()
                    .stationName(stop.stopName())
                    .stationId(stop.stopId())
                    .latitude(stop.lat())
                    .longitude(stop.lon())
                    .scheduledArrival(scheduledArr)
                    .scheduledDeparture(scheduledDep)
                    .expectedArrival(expectedArr)
                    .expectedDeparture(expectedDep)
                    .departed(departed)
                    .skipped(false)
                    .build());
        }
        return result;
    }

    private TrackingStatus computeStatus(List<StopInfo> stops) {
        if (stops == null || stops.isEmpty()) return TrackingStatus.UNKNOWN;
        LocalDateTime now = LocalDateTime.now(PARIS);
        LocalDateTime firstDep = coalesce(stops.get(0).getExpectedDeparture(), stops.get(0).getScheduledDeparture());
        LocalDateTime lastArr  = coalesce(stops.get(stops.size() - 1).getExpectedArrival(),
                                          stops.get(stops.size() - 1).getScheduledArrival());
        if (firstDep != null && now.isBefore(firstDep)) return TrackingStatus.SCHEDULED;
        if (lastArr  != null && now.isAfter(lastArr))   return TrackingStatus.ARRIVED;
        return TrackingStatus.IN_PROGRESS;
    }

    private TrackingResponse toResponse(TrackedJourney journey) {
        TrackingResponse.Builder builder = TrackingResponse.builder()
                .journeyId(journey.getId())
                .trainNumber(journey.getTrainNumber())
                .date(journey.getDate())
                .status(journey.getStatus())
                .lastUpdated(journey.getLastUpdated())
                .errorMessage(journey.getErrorMessage());

        if (journey.getStops() != null && !journey.getStops().isEmpty()) {
            List<StopInfo> stops = journey.getStops();
            StopInfo destination = stops.get(stops.size() - 1);
            builder.allStops(stops)
                   .destination(destination)
                   .nextStop(findNextStop(stops))
                   .currentPosition(computeCurrentPosition(stops))
                   .delayMinutes(computeDelayMinutes(destination))
                   .eta(coalesce(destination.getExpectedArrival(), destination.getScheduledArrival()));
        }
        return builder.build();
    }

    /** Interpolation linéaire de la position entre deux gares. */
    private TrainPosition computeCurrentPosition(List<StopInfo> stops) {
        LocalDateTime now = LocalDateTime.now(PARIS);

        // Trouve le dernier arrêt dont le train est parti
        int lastDepartedIdx = -1;
        for (int i = 0; i < stops.size(); i++) {
            LocalDateTime dep = coalesce(stops.get(i).getExpectedDeparture(), stops.get(i).getScheduledDeparture());
            if (dep != null && now.isAfter(dep)) lastDepartedIdx = i;
        }

        // Pas encore parti : position à la première gare
        if (lastDepartedIdx < 0) {
            return posAt(stops.get(0), now);
        }

        // Arrivé à destination
        if (lastDepartedIdx >= stops.size() - 1) {
            return posAt(stops.get(stops.size() - 1), now);
        }

        // En transit entre lastDepartedIdx et le suivant
        StopInfo from = stops.get(lastDepartedIdx);
        StopInfo to   = stops.get(lastDepartedIdx + 1);

        LocalDateTime dep = coalesce(from.getExpectedDeparture(), from.getScheduledDeparture());
        LocalDateTime arr = coalesce(to.getExpectedArrival(),     to.getScheduledArrival());

        if (dep == null || arr == null || !now.isBefore(arr)) {
            return posAt(to, now);
        }

        long total = ChronoUnit.SECONDS.between(dep, arr);
        if (total <= 0) return posAt(from, now);

        double progress = Math.min(1.0, (double) ChronoUnit.SECONDS.between(dep, now) / total);
        return TrainPosition.builder()
                .latitude(from.getLatitude()  + progress * (to.getLatitude()  - from.getLatitude()))
                .longitude(from.getLongitude() + progress * (to.getLongitude() - from.getLongitude()))
                .timestamp(now)
                .build();
    }

    private TrainPosition posAt(StopInfo stop, LocalDateTime now) {
        return TrainPosition.builder()
                .latitude(stop.getLatitude()).longitude(stop.getLongitude()).timestamp(now).build();
    }

    private StopInfo findNextStop(List<StopInfo> stops) {
        LocalDateTime now = LocalDateTime.now(PARIS);
        return stops.stream()
                .filter(s -> {
                    LocalDateTime arr = coalesce(s.getExpectedArrival(), s.getScheduledArrival());
                    return arr != null && arr.isAfter(now);
                })
                .findFirst()
                .orElse(null);
    }

    private int computeDelayMinutes(StopInfo destination) {
        if (destination.getExpectedArrival() == null || destination.getScheduledArrival() == null) return 0;
        return (int) ChronoUnit.MINUTES.between(
                destination.getScheduledArrival(), destination.getExpectedArrival());
    }

    /** Parse "HH:MM:SS" GTFS — gère le dépassement de minuit (ex: "25:10:00"). */
    private LocalDateTime parseGtfsTime(String time, LocalDate date) {
        if (time == null || time.isBlank()) return null;
        try {
            String[] parts = time.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            int s = Integer.parseInt(parts[2]);
            return date.atStartOfDay().plusHours(h).plusMinutes(m).plusSeconds(s);
        } catch (Exception e) {
            return null;
        }
    }

    private <T> T coalesce(T a, T b) { return a != null ? a : b; }
}
