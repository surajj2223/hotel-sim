package com.hotelops.paymentssim;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer<?> postgresContainer() {
		// Mirrors core-api's pattern, against postgres:16-alpine. payments-sim has no
		// JPA-mapped enums in 1A (entities deferred to 1B), so the stringtype=unspecified
		// param is omitted here and added alongside the first enum-bearing entity.
		return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
				.withDatabaseName("pspsim");
	}
}
