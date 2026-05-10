package com.TrainTracker.backend.model;

public enum TrackingStatus {
    SCHEDULED,   // Avant le départ
    IN_PROGRESS, // En route
    ARRIVED,     // Arrivé à destination
    CANCELLED,   // Annulé
    UNKNOWN      // Données indisponibles
}
