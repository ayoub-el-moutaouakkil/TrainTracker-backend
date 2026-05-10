package com.TrainTracker.backend.dto;

import java.time.LocalDate;

public class TrackJourneyRequest {

    private String trainNumber;
    private LocalDate date;

    public String getTrainNumber() { return trainNumber; }
    public void setTrainNumber(String trainNumber) { this.trainNumber = trainNumber; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
}
