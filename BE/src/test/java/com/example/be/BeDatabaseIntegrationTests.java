package com.example.be;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class BeDatabaseIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void oracleHealthAndFlywayHistoryAreAvailable() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/actuator/health"))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());

        Map<?, ?> health = new ObjectMapper().readValue(response.body(), Map.class);
        assertNotNull(health);
        assertEquals("UP", health.get("status"));

        Map<?, ?> components = assertInstanceOf(Map.class, health.get("components"));
        Map<?, ?> db = assertInstanceOf(Map.class, components.get("db"));
        assertEquals("UP", db.get("status"));

        List<String> versions = jdbcTemplate.queryForList("""
                SELECT "version"
                FROM "flyway_schema_history"
                WHERE "success" = 1
                ORDER BY "installed_rank"
                """, String.class);

        assertTrue(versions.containsAll(
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "27")));
    }
}
