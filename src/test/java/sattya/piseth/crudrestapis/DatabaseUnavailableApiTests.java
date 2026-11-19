package sattya.piseth.crudrestapis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.api.enabled=true", "app.api.issuer=https://identity.example", "app.api.audience=api",
        "app.api.jwk-set-uri=https://identity.example/jwks", "spring.flyway.enabled=true", "spring.flyway.schemas=app",
        "spring.flyway.default-schema=app", "spring.datasource.hikari.connection-timeout=500",
        "spring.datasource.hikari.validation-timeout=250"})
@AutoConfigureMockMvc
@Testcontainers
class DatabaseUnavailableApiTests {
    @Container static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.7-trixie").withInitScript("db/test-init.sql");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    // Authentication is covered with signed tokens elsewhere; this fixture isolates connection acquisition.
    @MockitoBean JwtDecoder decoder;
    @Autowired MockMvc mvc;
    @Autowired javax.sql.DataSource dataSource;

    @Test void stoppedDatabaseReturnsRetryableProblemAtTransactionStart() throws Exception {
        POSTGRES.stop();
        ((com.zaxxer.hikari.HikariDataSource) dataSource).getHikariPoolMXBean().softEvictConnections();
        mvc.perform(post("/api/v1/notifications").with(jwt().jwt(token -> token
                        .issuer("https://identity.example").subject("producer").claim("tenant_id", UUID.randomUUID().toString())
                        .claim("producer_id", "orders")).authorities(new SimpleGrantedAuthority("SCOPE_notifications:write")))
                .header("Idempotency-Key", "outage").contentType("application/json")
                .content("{\"type\":\"TYPE\",\"category\":\"CATEGORY\",\"title\":\"Title\",\"body\":\"Body\",\"recipientIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.errorId").isNotEmpty());
    }
}
