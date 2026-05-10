package com.TrainTracker.backend.controller;

import com.TrainTracker.backend.dto.TrackJourneyRequest;
import com.TrainTracker.backend.service.TrainTrackingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/journeys")
public class TrainTrackingController {

    private static final Pattern TRAIN_NUMBER = Pattern.compile("^[A-Z0-9]{1,10}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_FORMAT  = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern UUID_FORMAT  = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final TrainTrackingService trackingService;

    public TrainTrackingController(TrainTrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @PostMapping
    public ResponseEntity<?> startTracking(@RequestBody TrackJourneyRequest request) {
        if (request.getTrainNumber() == null || !TRAIN_NUMBER.matcher(request.getTrainNumber()).matches())
            return ResponseEntity.badRequest().body("Numéro de train invalide");
        if (request.getDate() == null || !DATE_FORMAT.matcher(request.getDate().toString()).matches())
            return ResponseEntity.badRequest().body("Date invalide");
        return ResponseEntity.ok(trackingService.startTracking(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getTracking(@PathVariable String id) {
        if (!UUID_FORMAT.matcher(id).matches()) return ResponseEntity.badRequest().build();
        return trackingService.getTracking(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/refresh")
    public ResponseEntity<?> refresh(@PathVariable String id,
                                     @RequestHeader(value = "X-Delete-Token", required = false) String token) {
        if (!UUID_FORMAT.matcher(id).matches()) return ResponseEntity.badRequest().build();
        if (token == null || !UUID_FORMAT.matcher(token).matches()) return ResponseEntity.status(403).build();
        return trackingService.refresh(id, token)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(403).build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> stopTracking(@PathVariable String id,
                                              @RequestHeader(value = "X-Delete-Token", required = false) String token) {
        if (!UUID_FORMAT.matcher(id).matches()) return ResponseEntity.badRequest().build();
        if (token == null || !UUID_FORMAT.matcher(token).matches()) return ResponseEntity.status(403).build();
        boolean removed = trackingService.stopTracking(id, token);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.status(403).build();
    }
}
