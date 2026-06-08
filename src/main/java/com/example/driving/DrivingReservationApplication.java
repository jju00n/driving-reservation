package com.example.driving;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class DrivingReservationApplication {

	public static void main(String[] args) {
		SpringApplication.run(DrivingReservationApplication.class, args);
	}

}
