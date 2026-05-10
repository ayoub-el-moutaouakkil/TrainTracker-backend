package com.TrainTracker.backend.dto;

import com.TrainTracker.backend.model.StopInfo;
import com.TrainTracker.backend.model.TrackingStatus;
import com.TrainTracker.backend.model.TrainPosition;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class TrackingResponse {

    private String journeyId;
    private String deleteToken;
    private String trainNumber;
    private LocalDate date;
    private TrackingStatus status;
    private TrainPosition currentPosition;
    private int delayMinutes;
    private LocalDateTime eta;
    private StopInfo nextStop;
    private StopInfo destination;
    private List<StopInfo> allStops;
    private LocalDateTime lastUpdated;
    private String errorMessage;

    private TrackingResponse() {}

    public static Builder builder() { return new Builder(); }

    public String getJourneyId() { return journeyId; }
    public String getDeleteToken() { return deleteToken; }
    public String getTrainNumber() { return trainNumber; }
    public LocalDate getDate() { return date; }
    public TrackingStatus getStatus() { return status; }
    public TrainPosition getCurrentPosition() { return currentPosition; }
    public int getDelayMinutes() { return delayMinutes; }
    public LocalDateTime getEta() { return eta; }
    public StopInfo getNextStop() { return nextStop; }
    public StopInfo getDestination() { return destination; }
    public List<StopInfo> getAllStops() { return allStops; }
    public LocalDateTime getLastUpdated() { return lastUpdated; }
    public String getErrorMessage() { return errorMessage; }

    public static class Builder {
        private final TrackingResponse obj = new TrackingResponse();

        public Builder journeyId(String v) { obj.journeyId = v; return this; }
        public Builder deleteToken(String v) { obj.deleteToken = v; return this; }
        public Builder trainNumber(String v) { obj.trainNumber = v; return this; }
        public Builder date(LocalDate v) { obj.date = v; return this; }
        public Builder status(TrackingStatus v) { obj.status = v; return this; }
        public Builder currentPosition(TrainPosition v) { obj.currentPosition = v; return this; }
        public Builder delayMinutes(int v) { obj.delayMinutes = v; return this; }
        public Builder eta(LocalDateTime v) { obj.eta = v; return this; }
        public Builder nextStop(StopInfo v) { obj.nextStop = v; return this; }
        public Builder destination(StopInfo v) { obj.destination = v; return this; }
        public Builder allStops(List<StopInfo> v) { obj.allStops = v; return this; }
        public Builder lastUpdated(LocalDateTime v) { obj.lastUpdated = v; return this; }
        public Builder errorMessage(String v) { obj.errorMessage = v; return this; }

        public TrackingResponse build() { return obj; }
    }
}
