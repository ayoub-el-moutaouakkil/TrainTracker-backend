package com.TrainTracker.backend.controller;

import com.TrainTracker.backend.service.GtfsStaticService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/diag")
public class DiagController {

    private final GtfsStaticService gtfsStatic;

    public DiagController(GtfsStaticService gtfsStatic) {
        this.gtfsStatic = gtfsStatic;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
            "gtfsLoaded", gtfsStatic.isLoaded(),
            "stopsCount", gtfsStatic.getStopsCount(),
            "tripsCount", gtfsStatic.getTripsCount()
        ));
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String q) {
        if (q == null || q.isBlank() || q.length() > 20)
            return ResponseEntity.badRequest().body("Paramètre invalide");
        List<String> results = gtfsStatic.searchHeadsigns(q);
        return ResponseEntity.ok(results);
    }

    @GetMapping("/trip")
    public ResponseEntity<?> trip(
            @RequestParam String number,
            @RequestParam(defaultValue = "") String date) {

        if (number == null || number.isBlank() || number.length() > 10)
            return ResponseEntity.badRequest().body("Numéro invalide");

        LocalDate d;
        try {
            d = date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body("Format de date invalide (attendu: yyyy-MM-dd)");
        }

        Optional<String> tripId = gtfsStatic.findTripId(number, d);
        return ResponseEntity.ok(Map.of(
            "trainNumber", number,
            "date", d.toString(),
            "found", tripId.isPresent(),
            "tripId", tripId.orElse("—")
        ));
    }
}
