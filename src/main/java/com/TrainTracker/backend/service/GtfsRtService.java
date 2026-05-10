package com.TrainTracker.backend.service;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class GtfsRtService {

    private static final Logger log = LoggerFactory.getLogger(GtfsRtService.class);

    private static final int FEED_ENTITY        = 2;
    private static final int ENTITY_TRIP_UPDATE = 4;
    private static final int TRIP_UPDATE_TRIP   = 1;
    private static final int TRIP_UPDATE_STU    = 2;
    private static final int TRIP_DESC_ID       = 1;
    private static final int STU_ARRIVAL        = 2;
    private static final int STU_DEPARTURE      = 3;
    private static final int STU_STOP_ID        = 4;
    private static final int STOP_EVENT_DELAY   = 3;

    @Value("${gtfs.rt.url}")
    private String gtfsRtUrl;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    private record CachedDelays(Map<String, Map<String, Integer>> data) {}
    private final AtomicReference<CachedDelays> cache = new AtomicReference<>(null);

    // Chargement initial au démarrage (thread séparé pour ne pas bloquer Spring)
    @PostConstruct
    public void init() {
        new Thread(this::refreshCache).start();
    }

    // Rafraîchissement toutes les 30s en arrière-plan
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void refreshCache() {
        cache.set(new CachedDelays(fetchAll()));
    }

    // Lecture instantanée depuis le cache — ne bloque jamais les threads HTTP
    public Map<String, Integer> getDelays(String tripId) {
        CachedDelays c = cache.get();
        return c != null ? c.data().getOrDefault(tripId, Map.of()) : Map.of();
    }

    // ─── Fetch ───────────────────────────────────────────────────────────────────

    private Map<String, Map<String, Integer>> fetchAll() {
        Map<String, Map<String, Integer>> allDelays = new HashMap<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(gtfsRtUrl))
                    .timeout(java.time.Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                parseAll(body, allDelays);
            }
            log.debug("GTFS-RT : {} trips avec retards", allDelays.size());
        } catch (Exception e) {
            log.warn("GTFS-RT indisponible : {}", e.getMessage());
        }
        return allDelays;
    }

    // ─── Parser protobuf ─────────────────────────────────────────────────────────

    private void parseAll(InputStream is, Map<String, Map<String, Integer>> out) throws Exception {
        CodedInputStream feed = CodedInputStream.newInstance(is);
        feed.setSizeLimit(50 * 1024 * 1024);
        int tag;
        while ((tag = feed.readTag()) != 0) {
            if (WireFormat.getTagFieldNumber(tag) == FEED_ENTITY) {
                parseEntity(feed.readByteArray(), out);
            } else {
                feed.skipField(tag);
            }
        }
    }

    private void parseEntity(byte[] bytes, Map<String, Map<String, Integer>> out) throws Exception {
        CodedInputStream entity = CodedInputStream.newInstance(bytes);
        int tag;
        while ((tag = entity.readTag()) != 0) {
            if (WireFormat.getTagFieldNumber(tag) == ENTITY_TRIP_UPDATE) {
                parseTripUpdate(entity.readByteArray(), out);
            } else {
                entity.skipField(tag);
            }
        }
    }

    private void parseTripUpdate(byte[] bytes, Map<String, Map<String, Integer>> out) throws Exception {
        String tripId = extractTripId(bytes);
        if (tripId.isEmpty()) return;
        Map<String, Integer> delays = new HashMap<>();
        CodedInputStream tu = CodedInputStream.newInstance(bytes);
        int tag;
        while ((tag = tu.readTag()) != 0) {
            if (WireFormat.getTagFieldNumber(tag) == TRIP_UPDATE_STU) {
                parseStopTimeUpdate(tu.readByteArray(), delays);
            } else {
                tu.skipField(tag);
            }
        }
        if (!delays.isEmpty()) out.put(tripId, delays);
    }

    private String extractTripId(byte[] bytes) throws Exception {
        CodedInputStream tu = CodedInputStream.newInstance(bytes);
        int tag;
        while ((tag = tu.readTag()) != 0) {
            if (WireFormat.getTagFieldNumber(tag) == TRIP_UPDATE_TRIP) {
                return extractStringField(tu.readByteArray(), TRIP_DESC_ID);
            } else {
                tu.skipField(tag);
            }
        }
        return "";
    }

    private void parseStopTimeUpdate(byte[] bytes, Map<String, Integer> delays) throws Exception {
        CodedInputStream stu = CodedInputStream.newInstance(bytes);
        String stopId = null;
        int delay = 0;
        int tag;
        while ((tag = stu.readTag()) != 0) {
            switch (WireFormat.getTagFieldNumber(tag)) {
                case STU_STOP_ID   -> stopId = stu.readString();
                case STU_ARRIVAL   -> delay = extractDelay(stu.readByteArray());
                case STU_DEPARTURE -> { if (delay == 0) delay = extractDelay(stu.readByteArray()); else stu.skipField(tag); }
                default            -> stu.skipField(tag);
            }
        }
        if (stopId != null) delays.put(stopId, delay);
    }

    private int extractDelay(byte[] bytes) throws Exception {
        CodedInputStream event = CodedInputStream.newInstance(bytes);
        int tag;
        while ((tag = event.readTag()) != 0) {
            if (WireFormat.getTagFieldNumber(tag) == STOP_EVENT_DELAY) return event.readInt32();
            else event.skipField(tag);
        }
        return 0;
    }

    private String extractStringField(byte[] bytes, int targetField) throws Exception {
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        int tag;
        while ((tag = input.readTag()) != 0) {
            if (WireFormat.getTagFieldNumber(tag) == targetField) return input.readString();
            else input.skipField(tag);
        }
        return "";
    }
}
