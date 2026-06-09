package com.hotelops.core;

import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.testcontainers.DockerClientFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base for all DataJpaTest integration tests.
 *
 * Uses Testcontainers PostgreSQL so that Postgres-specific features (native enum types,
 * CHECK constraints, partial indexes, views) are validated against a real database.
 * The {@code stringtype=unspecified} JDBC parameter is set in
 * {@link TestcontainersConfiguration} to allow Hibernate to write String values into
 * Postgres native enum columns.
 *
 * Tests in this class are skipped (assumed) when no container runtime (Docker/Colima/etc.)
 * is available — they do NOT fail; they are marked as skipped/ignored.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
public abstract class AbstractDataJpaTest {

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                isDockerAvailable(),
                "Skipping DataJPA integration tests: no container runtime available. " +
                "Start Docker/Colima/OrbStack to run these tests."
        );
    }

    private static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

