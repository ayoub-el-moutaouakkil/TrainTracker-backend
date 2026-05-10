package com.TrainTracker.backend.model;

import java.time.LocalDateTime;

public class StopInfo {

    private String stationName;
    private String stationId;
    private double latitude;
    private double longitude;
    private LocalDateTime scheduledDeparture;
    private LocalDateTime expectedDeparture;
    private LocalDateTime scheduledArrival;
    private LocalDateTime expectedArrival;
    private boolean departed;
    private boolean skipped;

    private StopInfo() {}

    public static Builder builder() { return new Builder(); }

    public String getStationName() { return stationName; }
    public String getStationId() { return stationId; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public LocalDateTime getScheduledDeparture() { return scheduledDeparture; }
    public LocalDateTime getExpectedDeparture() { return expectedDeparture; }
    public LocalDateTime getScheduledArrival() { return scheduledArrival; }
    public LocalDateTime getExpectedArrival() { return expectedArrival; }
    public boolean isDeparted() { return departed; }
    public boolean isSkipped() { return skipped; }

    public static class Builder {
        private final StopInfo obj = new StopInfo();

        public Builder stationName(String v) { obj.stationName = v; return this; }
        public Builder stationId(String v) { obj.stationId = v; return this; }
        public Builder latitude(double v) { obj.latitude = v; return this; }
        public Builder longitude(double v) { obj.longitude = v; return this; }
        public Builder scheduledDeparture(LocalDateTime v) { obj.scheduledDeparture = v; return this; }
        public Builder expectedDeparture(LocalDateTime v) { obj.expectedDeparture = v; return this; }
        public Builder scheduledArrival(LocalDateTime v) { obj.scheduledArrival = v; return this; }
        public Builder expectedArrival(LocalDateTime v) { obj.expectedArrival = v; return this; }
        public Builder departed(boolean v) { obj.departed = v; return this; }
        public Builder skipped(boolean v) { obj.skipped = v; return this; }

        public StopInfo build() { return obj; }
    }
}
