package com.TrainTracker.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TrainTrackerApplication {

	public static void main(String[] args) {
		SpringApplication.run(TrainTrackerApplication.class, args);
	}

}
