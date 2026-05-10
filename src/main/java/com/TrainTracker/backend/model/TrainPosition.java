package com.TrainTracker.backend.model;

import java.time.LocalDateTime;

public class TrainPosition {

    private double latitude;
    private double longitude;
    private LocalDateTime timestamp;

    private TrainPosition() {}

    public static Builder builder() { return new Builder(); }

    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public LocalDateTime getTimestamp() { return timestamp; }

    public static class Builder {
        private final TrainPosition obj = new TrainPosition();

        public Builder latitude(double v) { obj.latitude = v; return this; }
        public Builder longitude(double v) { obj.longitude = v; return this; }
        public Builder timestamp(LocalDateTime v) { obj.timestamp = v; return this; }

        public TrainPosition build() { return obj; }
    }
}
