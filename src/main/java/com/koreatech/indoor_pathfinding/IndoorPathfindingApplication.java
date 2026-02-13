package com.koreatech.indoor_pathfinding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class IndoorPathfindingApplication {

	public static void main(String[] args) {
		SpringApplication.run(IndoorPathfindingApplication.class, args);
	}

}
