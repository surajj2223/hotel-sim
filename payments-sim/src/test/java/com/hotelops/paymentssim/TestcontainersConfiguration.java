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
		// stringtype=unspecified lets Hibernate send String params that Postgres can
		// implicitly cast to the psp_payment_status / psp_refund_status enum types
		// (the @Enumerated(EnumType.STRING) fields on PspPayment / PspRefund). Same
		// idiom as core-api's application.yml.
		return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
				.withDatabaseName("pspsim")
				.withUrlParam("stringtype", "unspecified");
	}
}
