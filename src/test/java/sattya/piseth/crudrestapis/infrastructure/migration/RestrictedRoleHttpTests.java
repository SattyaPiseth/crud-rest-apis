package sattya.piseth.crudrestapis.infrastructure.migration;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import sattya.piseth.crudrestapis.recipient.domain.Recipient;
import sattya.piseth.crudrestapis.shared.audit.BackgroundActor;
import sattya.piseth.crudrestapis.shared.persistence.TenantDatabaseContext;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.api.enabled=true", "spring.flyway.enabled=false",
                "spring.datasource.hikari.maximum-pool-size=1"})
@Testcontainers
class RestrictedRoleHttpTests {
    static final String ISSUER = "https://restricted-http.example";
    static final RSAKey KEY;
    static final HttpServer JWKS;
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("restricted-http").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.createContext("/jwks", exchange -> {
                try (exchange) {
                    var bytes = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            JWKS.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @AfterAll static void stopJwks() { JWKS.stop(0); }
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.7-trixie");
    // Disposable test credentials only; never used by Compose or deployed environments.
    private static final String PASSWORD = "disposable-runtime-test";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) throws SQLException {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE crud_owner NOLOGIN");
            statement.execute("CREATE SCHEMA app AUTHORIZATION crud_owner");
            statement.execute("CREATE ROLE crud_app LOGIN PASSWORD '" + PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            statement.execute("GRANT USAGE ON SCHEMA app TO crud_app");
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE crud_owner IN SCHEMA app "
                    + "GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO crud_app");
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE crud_owner IN SCHEMA app "
                    + "GRANT USAGE, SELECT ON SEQUENCES TO crud_app");
        }
        DatabaseMigrator.migrate(Map.of("MIGRATION_DB_URL", POSTGRES.getJdbcUrl(),
                "MIGRATION_DB_USERNAME", POSTGRES.getUsername(), "MIGRATION_DB_PASSWORD", POSTGRES.getPassword()));
        properties.add("app.api.issuer", () -> ISSUER);
        properties.add("app.api.audience", () -> "http-test-audience");
        properties.add("app.api.role-source-client", () -> "http-test-client");
        properties.add("app.api.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", () -> "crud_app");
        properties.add("spring.datasource.password", () -> PASSWORD);
    }


    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TenantDatabaseContext tenantContext;
    @Autowired BackgroundActor actor;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;

    UUID seed(UUID tenant) {
        return actor.callAs("seed", () -> new TransactionTemplate(transactions).execute(status -> {
            tenantContext.setTenant(tenant);
            var recipient = new Recipient(tenant, ISSUER, "reader");
            entityManager.persist(recipient);
            return recipient.getId();
        }));
    }

    String token(UUID tenant, String roleClient, String audience, String scope) throws Exception {
        return token(tenant, roleClient, audience, scope, "reader");
    }

    String token(UUID tenant, String roleClient, String audience, String scope, String subject) throws Exception {
        var claims = new HashMap<String, Object>();
        claims.put("resource_access", Map.of(roleClient,
                Map.of("roles", List.of("notifications:write", "inbox:read", "inbox:write"))));
        if (scope != null) claims.put("scope", scope);
        return signedToken(tenant, subject, audience, claims);
    }

    String signedToken(UUID tenant, String subject, String audience, Map<String, Object> extraClaims) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience(audience)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("tenant_id", tenant.toString()).claim("producer_id", "orders");
        extraClaims.forEach(claims::claim);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    String auditIdentity(String subject) {
        return "jwt:" + ISSUER.length() + ":" + ISSUER + ":" + subject;
    }

    void assertInboxAudit(UUID tenant, String id, long version, boolean archived) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            tenantContext.setTenant(tenant);
            var row = jdbc.queryForMap("SELECT created_by, updated_by, version, read_at, archived_at "
                    + "FROM app.notification_recipient WHERE id = ?", UUID.fromString(id));
            assertThat(row).containsEntry("created_by", auditIdentity("producer"));
            assertThat(row).containsEntry("updated_by", auditIdentity("reader"));
            assertThat(((Number) row.get("version")).longValue()).isEqualTo(version);
            assertThat(row.get("read_at")).isNotNull();
            if (archived) assertThat(row.get("archived_at")).isNotNull();
            else assertThat(row.get("archived_at")).isNull();
        });
    }

    HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").header("Idempotency-Key", "http-key")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    String body(UUID recipient) {
        return mapper.writeValueAsString(Map.of("type", "TYPE", "category", "ORDERS", "title", "Title",
                "body", "Body", "recipientIds", List.of(recipient)));
    }

    @Test void signedHttpRequestsPreserveRlsAuditingIdempotencyAndPoolIsolation() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("crud_app");
        assertThat(jdbc.queryForObject("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isFalse();
        var backend = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        var a = UUID.randomUUID(); var b = UUID.randomUUID();
        var recipientA = seed(a); var recipientB = seed(b);
        var tokenA = token(a, "http-test-client", "http-test-audience", null);
        var tokenB = token(b, "http-test-client", "http-test-audience", null);
        var producerA = token(a, "http-test-client", "http-test-audience", null, "producer");
        var producerB = token(b, "http-test-client", "http-test-audience", null, "producer");
        var created = request("POST", "/notifications", producerA, body(recipientA));
        assertThat(created.statusCode()).isEqualTo(201);
        var replay = request("POST", "/notifications", producerA, body(recipientA));
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(replay.body()).path("replayed").asBoolean()).isTrue();
        assertThat(mapper.readTree(replay.body()).path("notificationId")).isEqualTo(mapper.readTree(created.body()).path("notificationId"));
        assertThat(request("POST", "/notifications", producerB, body(recipientA)).statusCode()).isEqualTo(400);
        assertThat(request("POST", "/notifications", producerB, body(recipientB)).statusCode()).isEqualTo(201);
        var listA = request("GET", "/inbox", tokenA, null);
        var listB = request("GET", "/inbox", tokenB, null);
        assertThat(listA.statusCode()).isEqualTo(200);
        assertThat(listB.statusCode()).isEqualTo(200);
        var itemsA = mapper.readTree(listA.body()).path("items");
        var itemsB = mapper.readTree(listB.body()).path("items");
        assertThat(itemsA.size()).isEqualTo(1); assertThat(itemsB.size()).isEqualTo(1);
        var id = itemsA.get(0).path("id").asString();
        assertThat(itemsB.get(0).path("id").asString()).isNotEqualTo(id);
        assertThat(request("GET", "/inbox/" + id, tokenB, null).statusCode()).isEqualTo(404);
        assertThat(request("PUT", "/inbox/" + id + "/read", tokenB, "{\"version\":0}").statusCode()).isEqualTo(404);
        for (var tenant : List.of(a, b)) {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                tenantContext.setTenant(tenant);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM app.notification", Long.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM app.ingestion_request", Long.class)).isEqualTo(1);
                for (var table : List.of("notification", "notification_recipient", "outbox_event", "ingestion_request")) {
                    assertThat(jdbc.queryForObject("SELECT created_by FROM app." + table, String.class))
                            .as("%s creation actor for tenant %s", table, tenant)
                            .isEqualTo(auditIdentity("producer"));
                }
                if (tenant.equals(b)) {
                    assertThat(jdbc.queryForObject("SELECT updated_by FROM app.notification_recipient", String.class))
                            .isEqualTo(auditIdentity("producer"));
                    assertThat(jdbc.queryForObject("SELECT version FROM app.notification_recipient", Long.class)).isZero();
                }
            });
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.notification", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT NULLIF(current_setting('app.tenant_id',true),'')", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(backend);
    }

    @Test void recipientLifecyclePreservesAuditAndRejectsStaleMutations() throws Exception {
        var a = UUID.randomUUID();
        var recipient = seed(a);
        var tokenA = token(a, "http-test-client", "http-test-audience", null);
        var producer = token(a, "http-test-client", "http-test-audience", null, "producer");
        assertThat(request("POST", "/notifications", producer, body(recipient)).statusCode()).isEqualTo(201);
        var list = request("GET", "/inbox", tokenA, null);
        assertThat(list.statusCode()).isEqualTo(200);
        var items = mapper.readTree(list.body()).path("items");
        assertThat(items.size()).isEqualTo(1);
        var id = items.get(0).path("id").asString();
        assertThat(request("GET", "/inbox/" + id, tokenA, null).statusCode()).isEqualTo(200);
        assertThat(request("PUT", "/inbox/" + id + "/read", tokenA, "{\"version\":0}").statusCode()).isEqualTo(200);
        assertInboxAudit(a, id, 1, false);
        assertThat(request("PUT", "/inbox/" + id + "/archive", tokenA, "{\"version\":1}").statusCode()).isEqualTo(200);
        assertInboxAudit(a, id, 2, true);
        var afterArchive = request("GET", "/inbox", tokenA, null);
        assertThat(afterArchive.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(afterArchive.body()).path("items").size()).isZero();
        var detail = request("GET", "/inbox/" + id, tokenA, null);
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(detail.body()).path("version").asLong()).isEqualTo(2);
        var beforeRejected = new TransactionTemplate(transactions).execute(status -> {
            tenantContext.setTenant(a);
            return jdbc.queryForMap("SELECT * FROM app.notification_recipient WHERE id=?", UUID.fromString(id));
        });
        for (var action : List.of("read", "archive")) {
            assertThat(request("PUT", "/inbox/" + id + "/" + action, tokenA, "{\"version\":0}")
                    .statusCode()).isEqualTo(409);
        }
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            tenantContext.setTenant(a);
            assertThat(jdbc.queryForMap("SELECT * FROM app.notification_recipient WHERE id=?", UUID.fromString(id)))
                    .isEqualTo(beforeRejected);
        });
    }

    @Test void startupGuardFailsClosedAndOnlyStartsForVerifiedUnaffectedFlags() throws Exception {
        var script = java.nio.file.Files.readString(java.nio.file.Path.of("docker/keycloak/start-guard.sh"))
                .replace("/proc/cpuinfo", "/tmp/guard-cpu-fixture")
                .replace("exec /opt/keycloak/bin/kc.sh \"$@\"", "echo GUARD_ALLOWED_START");
        for (var fixture : new String[]{"flags : avx512vbmi", "flags : avx512_vbmi",
                "flags : avx2 sse2", "", "processor : 0"}) {
            var result = POSTGRES.execInContainer("sh", "-c",
                    "printf '%s\\n' '" + fixture + "' > /tmp/guard-cpu-fixture; " + script);
            boolean allowed = fixture.equals("flags : avx2 sse2");
            assertThat(result.getExitCode()).as(fixture).isEqualTo(allowed ? 0 : 78);
            assertThat(result.getStdout().contains("GUARD_ALLOWED_START")).isEqualTo(allowed);
        }
        var missing = POSTGRES.execInContainer("sh", "-c",
                script.replace("/tmp/guard-cpu-fixture", "/missing-cpu-info"));
        assertThat(missing.getExitCode()).isEqualTo(78);
        assertThat(missing.getStdout()).doesNotContain("GUARD_ALLOWED_START");
        var readFailure = POSTGRES.execInContainer("sh", "-c",
                script.replace("/tmp/guard-cpu-fixture", "/tmp"));
        assertThat(readFailure.getExitCode()).isEqualTo(78);
        assertThat(readFailure.getStdout()).doesNotContain("GUARD_ALLOWED_START");
    }

    @Test void configuredRoleClientIsIndependentOfAudienceAndPreservesScopes() throws Exception {
        var tenant = UUID.randomUUID(); seed(tenant);
        assertThat(request("GET", "/inbox", token(tenant, "http-test-client", "http-test-audience", null), null).statusCode()).isEqualTo(200);
        for (var wrongClient : List.of("notification-api", "http-test-audience", "unrelated")) {
            assertThat(request("GET", "/inbox", token(tenant, wrongClient, "http-test-audience", null), null).statusCode()).isEqualTo(403);
        }
        assertThat(request("GET", "/inbox", token(tenant, "unrelated", "http-test-audience", "inbox:read"), null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/inbox", token(tenant, "http-test-client", "http-test-client", null), null).statusCode()).isEqualTo(401);
    }

    @Test void archivingAnUnreadItemReplacesTheProducerAuditIdentity() throws Exception {
        var tenant = UUID.randomUUID(); var recipient = seed(tenant);
        var producer = token(tenant, "http-test-client", "http-test-audience", null, "producer");
        var reader = token(tenant, "http-test-client", "http-test-audience", null);
        assertThat(request("POST", "/notifications", producer, body(recipient)).statusCode()).isEqualTo(201);
        var list = request("GET", "/inbox", reader, null);
        assertThat(list.statusCode()).isEqualTo(200);
        var id = UUID.fromString(mapper.readTree(list.body()).path("items").get(0).path("id").asString());
        assertThat(request("PUT", "/inbox/" + id + "/archive", reader, "{\"version\":0}").statusCode()).isEqualTo(200);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            tenantContext.setTenant(tenant);
            var row = jdbc.queryForMap("SELECT created_by, updated_by, version, read_at, archived_at "
                    + "FROM app.notification_recipient WHERE id = ?", id);
            assertThat(row).containsEntry("created_by", auditIdentity("producer"));
            assertThat(row).containsEntry("updated_by", auditIdentity("reader"));
            assertThat(((Number) row.get("version")).longValue()).isEqualTo(1);
            assertThat(row.get("read_at")).isNull();
            assertThat(row.get("archived_at")).isNotNull();
        });
    }

    @Test void malformedRoleClaimsDoNotGrantAuthoritiesOrBreakValidScopes() throws Exception {
        var tenant = UUID.randomUUID(); seed(tenant);
        var malformedResources = Arrays.<Object>asList(null, "inbox:read", 42, List.of("inbox:read"),
                Map.of("http-test-client", "inbox:read"),
                Map.of("http-test-client", List.of("inbox:read")),
                Map.of("http-test-client", Map.of()),
                Map.of("http-test-client", Map.of("roles", "inbox:read")),
                Map.of("http-test-client", Map.of("roles", Map.of("inbox:read", true))),
                Map.of("http-test-client", Map.of("roles", Arrays.asList(null, 42, true, Map.of("role", "inbox:read")))));
        for (var resource : malformedResources) {
            var claims = new HashMap<String, Object>();
            claims.put("resource_access", resource);
            var denied = request("GET", "/inbox", signedToken(tenant, "reader", "http-test-audience", claims), null);
            assertThat(denied.statusCode()).as("Malformed resource_access: %s", resource).isEqualTo(403);
            assertThat(denied.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("insufficient_scope");
            assertThat(mapper.readTree(denied.body()).path("status").asInt()).isEqualTo(403);
            claims.put("scope", "inbox:read");
            assertThat(request("GET", "/inbox", signedToken(tenant, "reader", "http-test-audience", claims), null)
                    .statusCode()).isEqualTo(200);
        }
    }

    @Test void mixedRoleArraysGrantOnlyTheirStringAuthorities() throws Exception {
        var tenant = UUID.randomUUID(); var recipient = seed(tenant);
        var claims = Map.<String, Object>of("resource_access", Map.of("http-test-client", Map.of("roles",
                Arrays.asList("inbox:read", null, 42, true, Map.of("role", "notifications:write")))));
        var signed = signedToken(tenant, "reader", "http-test-audience", claims);
        assertThat(request("GET", "/inbox", signed, null).statusCode()).isEqualTo(200);
        var denied = request("POST", "/notifications", signed, body(recipient));
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("insufficient_scope");
    }

    @Test void realmOnlyRolesDoNotGrantApiPermissions() throws Exception {
        var tenant = UUID.randomUUID(); var recipient = seed(tenant);
        var claims = Map.<String, Object>of("realm_access",
                Map.of("roles", List.of("inbox:read", "inbox:write", "notifications:write")));
        var signed = signedToken(tenant, "reader", "http-test-audience", claims);
        for (var response : List.of(request("GET", "/inbox", signed, null),
                request("POST", "/notifications", signed, body(recipient)),
                request("PUT", "/inbox/" + UUID.randomUUID() + "/read", signed, "{\"version\":0}"))) {
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("insufficient_scope");
        }
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            tenantContext.setTenant(tenant);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app.ingestion_request", Long.class)).isZero();
        });
    }

}
