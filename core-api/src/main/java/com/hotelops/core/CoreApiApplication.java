package com.hotelops.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class CoreApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(CoreApiApplication.class, args);
	}

}
