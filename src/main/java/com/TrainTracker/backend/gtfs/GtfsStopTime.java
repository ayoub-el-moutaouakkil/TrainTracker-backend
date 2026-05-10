package com.TrainTracker.backend.gtfs;

public record GtfsStopTime(
        String tripId,
        String stopId,
        String arrivalTime,     // "HH:MM:SS", peut dépasser 24:00:00
        String departureTime,   // "HH:MM:SS"
        int stopSequence
) {}
