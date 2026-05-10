package com.TrainTracker.backend.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class TrackedJourney {

    private String id;
    private String deleteToken;
    private String trainNumber;
    private LocalDate date;
    private String vehicleJourneyId;
    private List<StopInfo> stops;
    private TrackingStatus status;
    private LocalDateTime lastUpdated;
    private String errorMessage;

    private TrackedJourney() {}

    public static Builder builder() { return new Builder(); }

    public String getId() { return id; }
    public String getDeleteToken() { return deleteToken; }
    public String getTrainNumber() { return trainNumber; }
    public LocalDate getDate() { return date; }
    public String getVehicleJourneyId() { return vehicleJourneyId; }
    public List<StopInfo> getStops() { return stops; }
    public TrackingStatus getStatus() { return status; }
    public LocalDateTime getLastUpdated() { return lastUpdated; }
    public String getErrorMessage() { return errorMessage; }

    public void setStops(List<StopInfo> stops) { this.stops = stops; }
    public void setStatus(TrackingStatus status) { this.status = status; }
    public void setLastUpdated(LocalDateTime lastUpdated) { this.lastUpdated = lastUpdated; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public static class Builder {
        private final TrackedJourney obj = new TrackedJourney();

        public Builder id(String v) { obj.id = v; return this; }
        public Builder deleteToken(String v) { obj.deleteToken = v; return this; }
        public Builder trainNumber(String v) { obj.trainNumber = v; return this; }
        public Builder date(LocalDate v) { obj.date = v; return this; }
        public Builder vehicleJourneyId(String v) { obj.vehicleJourneyId = v; return this; }
        public Builder stops(List<StopInfo> v) { obj.stops = v; return this; }
        public Builder status(TrackingStatus v) { obj.status = v; return this; }
        public Builder lastUpdated(LocalDateTime v) { obj.lastUpdated = v; return this; }
        public Builder errorMessage(String v) { obj.errorMessage = v; return this; }

        public TrackedJourney build() { return obj; }
    }
}
