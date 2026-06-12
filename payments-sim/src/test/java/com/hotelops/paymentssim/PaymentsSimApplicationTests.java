package com.hotelops.paymentssim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.DockerClientFactory;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentsSimApplicationTests {

	@BeforeAll
	static void requireDocker() {
		try {
			DockerClientFactory.instance().client();
		} catch (Exception e) {
			Assumptions.abort("Skipping context-load test: no container runtime available.");
		}
	}

	@Autowired
	DataSource dataSource;

	@Test
	void contextLoads() {
	}

	@Test
	void v1MigrationApplied() throws Exception {
		List<String> versions = new ArrayList<>();
		try (var conn = dataSource.getConnection();
				var stmt = conn.createStatement();
				var rs = stmt.executeQuery(
						"SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank")) {
			while (rs.next()) {
				versions.add(rs.getString(1));
			}
		}
		assertThat(versions).containsExactly("1", "2");
	}

	@Test
	void v1TablesExist() throws Exception {
		List<String> tables = new ArrayList<>();
		try (var conn = dataSource.getConnection();
				var stmt = conn.createStatement();
				var rs = stmt.executeQuery(
						"SELECT table_name FROM information_schema.tables "
								+ "WHERE table_schema = 'public' AND table_name LIKE 'psp_%' "
								+ "ORDER BY table_name")) {
			while (rs.next()) {
				tables.add(rs.getString(1));
			}
		}
		assertThat(tables).containsExactly("psp_event_sequence", "psp_payment", "psp_refund");
	}
}
