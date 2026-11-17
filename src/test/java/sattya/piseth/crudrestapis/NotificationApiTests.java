package sattya.piseth.crudrestapis;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import java.util.concurrent.*;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import sattya.piseth.crudrestapis.recipient.domain.Recipient;
import sattya.piseth.crudrestapis.shared.audit.BackgroundActor;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.api.enabled=true", "spring.flyway.enabled=true",
        "spring.flyway.schemas=app", "spring.flyway.default-schema=app"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Testcontainers
class NotificationApiTests {
    static final String ISSUER = "https://identity.example";
    static final RSAKey KEY;
    static final ECKey EC_KEY;
    static final HttpServer JWKS;
    static final AtomicBoolean REDIRECT_JWKS = new AtomicBoolean();
    static final AtomicInteger REDIRECT_TARGET_REQUESTS = new AtomicInteger();
    static final ExecutorService JWKS_EXECUTOR = Executors.newCachedThreadPool();
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            EC_KEY = new ECKeyGenerator(Curve.P_256).keyID("ec-key").generate();
            JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JWKS.setExecutor(JWKS_EXECUTOR);
            JWKS.createContext("/jwks", exchange -> {
                if (REDIRECT_JWKS.get()) {
                    exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks-redirect-target");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                    return;
                }
                var bytes = new JWKSet(List.of(KEY.toPublicJWK(), EC_KEY.toPublicJWK())).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
            });
            JWKS.createContext("/jwks-redirect-target", exchange -> {
                REDIRECT_TARGET_REQUESTS.incrementAndGet();
                var bytes = new JWKSet(List.of(KEY.toPublicJWK(), EC_KEY.toPublicJWK())).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
            });
            JWKS.start();
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    @Container static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18.7-trixie").withInitScript("db/test-init.sql");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("app.api.issuer", () -> ISSUER);
        properties.add("app.api.audience", () -> "notifications");
        properties.add("app.api.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    }
    @AfterAll static void stopJwks() {
        JWKS.stop(0);
        JWKS_EXECUTOR.shutdownNow();
    }
    @Autowired MockMvc mvc;
    @org.springframework.boot.test.web.server.LocalServerPort int serverPort;
    @Autowired JwtDecoder decoder;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @Autowired BackgroundActor actor;
    @Autowired JdbcTemplate jdbc;
    UUID tenant;
    UUID recipient;
    String subject;

    @BeforeEach void seed() {
        tenant = UUID.randomUUID(); subject = UUID.randomUUID().toString();
        recipient = seedRecipient(tenant, subject);
    }

    UUID seedRecipient(UUID tenantId, String user) {
        return actor.callAs("seed", () -> new TransactionTemplate(transactions).execute(status -> {
            var r = new Recipient(tenantId, ISSUER, user); entityManager.persist(r); return r.getId();
        }));
    }

    String token(UUID tenantId, String user, String scopes) throws Exception {
        return token(tenantId, user, scopes, ISSUER, "notifications", Instant.now().plusSeconds(300), KEY);
    }
    String token(UUID tenantId, String user, String scopes, String issuer, String audience, Instant expiry, RSAKey key) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(user).audience(audience)
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(expiry))
                .claim("tenant_id", tenantId == null ? "invalid" : tenantId.toString())
                .claim("client_id", "orders").claim("scope", scopes).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        jwt.sign(new RSASSASigner(key)); return jwt.serialize();
    }
    String body(UUID recipientId, String title) {
        return mapper.writeValueAsString(Map.of("type", "TYPE", "category", "ORDERS", "title", title,
                "body", "Body", "recipientIds", List.of(recipientId)));
    }
    String producer() throws Exception { return token(tenant, subject, "notifications:write"); }
    String reader() throws Exception { return token(tenant, subject, "inbox:read inbox:write"); }
    UUID create() throws Exception {
        mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer " + producer())
                .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(body(recipient, "Title")))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT id FROM app.notification_recipient WHERE tenant_id = ?", UUID.class, tenant);
    }

    @Test void signedTokenCreatesReplaysConflictsAndAudits() throws Exception {
        create();
        mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer " + producer())
                .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(body(recipient, "Title")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer " + producer())
                .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(body(recipient, "Changed")))
                .andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        for (var table : List.of("notification", "notification_recipient", "outbox_event", "ingestion_request")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app." + table + " WHERE tenant_id = ?", Long.class, tenant)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT created_by FROM app." + table + " WHERE tenant_id = ?", String.class, tenant))
                    .isEqualTo("jwt:" + ISSUER.length() + ":" + ISSUER + ":" + subject);
        }
    }

    @Test void rejectsMissingInvalidExpiredWrongIssuerAudienceAndSignature() throws Exception {
        mvc.perform(get("/api/v1/inbox")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
        var otherKey = new RSAKeyGenerator(2048).generate();
        var tokens = List.of("garbage", token(null, subject, "inbox:read"),
                token(tenant, subject, "inbox:read", ISSUER, "other", Instant.now().plusSeconds(300), KEY),
                token(tenant, subject, "inbox:read", "https://other.example", "notifications", Instant.now().plusSeconds(300), KEY),
                token(tenant, subject, "inbox:read", ISSUER, "notifications", Instant.now().minusSeconds(120), KEY),
                token(tenant, subject, "inbox:read", ISSUER, "notifications", Instant.now().plusSeconds(300), otherKey));
        for (var token : tokens) mvc.perform(get("/api/v1/inbox").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
    }

    @Test void csrfRejectsUnsafeRequestsWithoutBearerCredentials() throws Exception {
        mvc.perform(post("/api/v1/notifications").header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON).content(body(recipient, "Title")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer invalid")
                        .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON)
                        .content(body(recipient, "Title")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
    }

    @Test void acceptsOnlyPinnedRs256AndDoesNotFollowJwkRedirects() throws Exception {
        var unsupported = List.of(
                signedToken(JWSAlgorithm.RS512, KEY),
                signedToken(JWSAlgorithm.ES256, EC_KEY),
                signedToken(JWSAlgorithm.HS256, null),
                new PlainJWT(baseClaims().build()).serialize());
        for (var value : unsupported) {
            mvc.perform(get("/api/v1/inbox").header("Authorization", "Bearer " + value))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
        }

        var unknownKey = new RSAKeyGenerator(2048).keyID("redirect-target-key").generate();
        REDIRECT_JWKS.set(true);
        try {
            assertThatThrownBy(() -> decoder.decode(signedToken(JWSAlgorithm.RS256, unknownKey)))
                    .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
            assertThat(REDIRECT_TARGET_REQUESTS).hasValue(0);
        } finally {
            REDIRECT_JWKS.set(false);
        }
    }

    private String signedToken(JWSAlgorithm algorithm, JWK key) throws Exception {
        var header = new JWSHeader.Builder(algorithm).keyID(key == null ? "hmac-key" : key.getKeyID()).build();
        var token = new SignedJWT(header, baseClaims().build());
        if (key instanceof RSAKey rsa) token.sign(new RSASSASigner(rsa));
        else if (key instanceof ECKey ec) token.sign(new ECDSASigner(ec));
        else token.sign(new MACSigner("0123456789ABCDEF0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII)));
        return token.serialize();
    }

    @Test void enforcesScopeAndActiveMembership() throws Exception {
        mvc.perform(get("/api/v1/inbox").header("Authorization", "Bearer " + producer())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer " + reader())
                .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(body(recipient, "Title")))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE app.recipient SET active=false WHERE id=?", recipient);
        mvc.perform(get("/api/v1/inbox").header("Authorization", "Bearer " + reader())).andExpect(status().isForbidden());
    }

    @Test void isolatesTenantAndOwnerForReadsAndWrites() throws Exception {
        var id = create();
        var otherTenant = UUID.randomUUID(); seedRecipient(otherTenant, subject);
        var otherSubject = UUID.randomUUID().toString(); seedRecipient(tenant, otherSubject);
        for (var token : List.of(token(otherTenant, subject, "inbox:read inbox:write"), token(tenant, otherSubject, "inbox:read inbox:write"))) {
            mvc.perform(get("/api/v1/inbox").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
            mvc.perform(get("/api/v1/inbox/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
            mvc.perform(put("/api/v1/inbox/" + id + "/archive").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isNotFound());
        }
        assertThat(jdbc.queryForObject("SELECT archived_at FROM app.notification_recipient WHERE id=?", Object.class, id)).isNull();
    }

    @Test void readArchiveVersionAndPaginationContract() throws Exception {
        var id = create();
        mvc.perform(get("/api/v1/inbox?size=1").header("Authorization", "Bearer " + reader()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].title").value("Title"))
                .andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(put("/api/v1/inbox/" + id + "/read").header("Authorization", "Bearer " + reader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readAt").isNotEmpty()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(put("/api/v1/inbox/" + id + "/archive").header("Authorization", "Bearer " + reader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/inbox/" + id + "/archive").header("Authorization", "Bearer " + reader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/v1/inbox").header("Authorization", "Bearer " + reader()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/inbox/" + id).header("Authorization", "Bearer " + reader())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT updated_by FROM app.notification_recipient WHERE id=?", String.class, id))
                .isEqualTo("jwt:" + ISSUER.length() + ":" + ISSUER + ":" + subject);
    }

    @Test void failedAcceptanceReturnsSafeProblemAndLeavesKeyRetryable(CapturedOutput output) throws Exception {
        jdbc.execute("ALTER TABLE app.outbox_event ADD CONSTRAINT api_injected_failure CHECK (tenant_id <> '" + tenant + "'::uuid)");
        try {
            mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer " + producer())
                    .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(body(recipient, "Title")))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.detail").value("An unexpected server error occurred"));
        } finally {
            jdbc.execute("ALTER TABLE app.outbox_event DROP CONSTRAINT api_injected_failure");
        }
        for (var table : List.of("notification", "notification_recipient", "outbox_event", "ingestion_request")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app." + table + " WHERE tenant_id=?", Long.class, tenant)).isZero();
        }
        assertThat(output.getAll()).contains("API failure id=").doesNotContain("Failing row contains", "api_injected_failure", "notification.created");
        create();
    }

    @Test void paginationHasNextAndRepeatedReadDoesNotChangeVersion() throws Exception {
        var first = create();
        mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer " + producer())
                .header("Idempotency-Key", "second").contentType(MediaType.APPLICATION_JSON).content(body(recipient, "Second")))
                .andExpect(status().isCreated());
        var page0 = mvc.perform(get("/api/v1/inbox?size=1").header("Authorization", "Bearer " + reader()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasNext").value(true)).andReturn().getResponse().getContentAsString();
        var page1 = mvc.perform(get("/api/v1/inbox?size=1&page=1").header("Authorization", "Bearer " + reader()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasNext").value(false)).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(page0).get("items").get(0).get("id").asString())
                .isNotEqualTo(mapper.readTree(page1).get("items").get(0).get("id").asString());
        mvc.perform(put("/api/v1/inbox/" + first + "/read").header("Authorization", "Bearer " + reader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}")).andExpect(status().isOk());
        var timestamp = jdbc.queryForObject("SELECT read_at FROM app.notification_recipient WHERE id=?", Object.class, first);
        mvc.perform(put("/api/v1/inbox/" + first + "/read").header("Authorization", "Bearer " + reader())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        assertThat(jdbc.queryForObject("SELECT read_at FROM app.notification_recipient WHERE id=?", Object.class, first)).isEqualTo(timestamp);
    }

    @Test void missingProducerClaimIsForbiddenAndMissingExpiryIsUnauthorized() throws Exception {
        var base = new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience("notifications")
                .claim("tenant_id", tenant.toString()).claim("scope", "notifications:write");
        var missingExpiry = sign(base.build());
        mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer " + missingExpiry)
                .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(body(recipient, "Title")))
                .andExpect(status().isUnauthorized());
        var missingProducer = sign(base.expirationTime(Date.from(Instant.now().plusSeconds(300))).build());
        mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer " + missingProducer)
                .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(body(recipient, "Title")))
                .andExpect(status().isForbidden());
    }

    @Test void readAndWriteScopesAreIndependentAndUnsupportedRoutesStayDenied() throws Exception {
        var id = create();
        var readOnly = token(tenant, subject, "inbox:read");
        var writeOnly = token(tenant, subject, "inbox:write");
        mvc.perform(put("/api/v1/inbox/" + id + "/read").header("Authorization", "Bearer " + readOnly)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden()).andExpect(header().string("WWW-Authenticate", containsString("insufficient_scope")));
        mvc.perform(get("/api/v1/inbox/" + id).header("Authorization", "Bearer " + writeOnly)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/inbox/" + id + "/read").header("Authorization", "Bearer " + writeOnly)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.title").doesNotExist()).andExpect(jsonPath("$.body").doesNotExist())
                .andExpect(jsonPath("$.notificationId").doesNotExist());
        mvc.perform(delete("/api/v1/inbox/" + id).header("Authorization", "Bearer " + reader())).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/info").header("Authorization", "Bearer " + reader())).andExpect(status().isForbidden());
    }

    @Test void healthProbesArePublicAndDoNotExposeComponentDetails() throws Exception {
        for (var path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components").doesNotExist());
        }
    }

    JWTClaimsSet.Builder baseClaims() {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience("notifications")
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).claim("tenant_id", tenant.toString());
    }

    @Test void validatesClaimTypesNotBeforeAndScpCollections() throws Exception {
        var invalid = List.of(baseClaims().subject(null).build(), baseClaims().subject(" ").build(),
                baseClaims().claim("sub", 42).build(), baseClaims().claim("tenant_id", List.of(tenant.toString())).build(),
                baseClaims().claim("scope", List.of(42)).build(), baseClaims().claim("scp", Map.of("read", true)).build(),
                baseClaims().notBeforeTime(Date.from(Instant.now().plusSeconds(120))).build());
        for (var claims : invalid) {
            mvc.perform(get("/api/v1/inbox").header("Authorization", "Bearer " + sign(claims)))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
        }
        mvc.perform(get("/api/v1/inbox").header("Authorization", "Bearer " + sign(baseClaims().claim("scp", List.of("inbox:read")).build())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/inbox").header("Authorization", "Bearer bad token"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
        // Unsupported query-token transport cannot replace or override the header token.
        mvc.perform(get("/api/v1/inbox").header("Authorization", "Bearer " + reader()).param("access_token", "one", "two"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/inbox").param("access_token", reader())).andExpect(status().isUnauthorized());
    }

    @Test void validatesAllHttpBoundaries() throws Exception {
        var id = create();
        for (var key : List.of(" ", "x".repeat(201))) {
            mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer " + producer())
                    .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body(recipient, "Title")))
                    .andExpect(status().isBadRequest());
        }

        for (var ids : List.of(Arrays.asList(recipient, null), Collections.nCopies(1001, recipient))) {
            var payload = mapper.writeValueAsString(Map.of("type", "TYPE", "category", "ORDERS", "title", "Title", "body", "Body", "recipientIds", ids));
            mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer " + producer())
                    .header("Idempotency-Key", "invalid").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isBadRequest());
        }
        for (var version : List.of("{}", "{\"version\":-1}", "{\"version\":null}")) {
            mvc.perform(put("/api/v1/inbox/" + id + "/read").header("Authorization", "Bearer " + reader())
                    .contentType(MediaType.APPLICATION_JSON).content(version)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer " + producer())
                .header("Idempotency-Key", "invalid").contentType(MediaType.TEXT_PLAIN).content("text")).andExpect(status().isUnsupportedMediaType());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.ingestion_request WHERE tenant_id=?", Long.class, tenant)).isEqualTo(1L);
    }

    @Test void rejectsRequestsLargerThanTheConfiguredApiLimitBeforeJsonParsing() throws Exception {
        var oversized = "{\"type\":\"TYPE\",\"category\":\"CATEGORY\",\"title\":\"Title\",\"body\":\""
                + "x".repeat(262144) + "\",\"recipientIds\":[\"" + UUID.randomUUID() + "\"]}";
        mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer " + producer())
                        .header("Idempotency-Key", "oversized").contentType(MediaType.APPLICATION_JSON).content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(413));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.ingestion_request WHERE tenant_id=?", Long.class, tenant)).isZero();
    }

    @Test void rejectsUnknownLengthChunkedUploadAtTheServletBoundary() throws Exception {
        var body = new byte[256 * 1024 + 1];
        var publisher = java.net.http.HttpRequest.BodyPublishers.ofInputStream(
                () -> new java.io.ByteArrayInputStream(body));
        assertThat(publisher.contentLength()).isEqualTo(-1);
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://127.0.0.1:" + serverPort + "/api/v1/notifications"))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + producer())
                .header("Idempotency-Key", "chunked-oversized")
                .header("Content-Type", "application/json")
                .POST(publisher)
                .build();
        var client = java.net.http.HttpClient.newBuilder()
                .proxy(java.net.http.HttpClient.Builder.NO_PROXY)
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();

        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        assertThat(response.body()).contains("\"status\":413");
    }

    @Test void simultaneousMutationsProduceOneSuccessAndOneConflict() throws Exception {
        var id = create(); var token = reader();
        try (var blocker = jdbc.getDataSource().getConnection(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try {
                int pid;
                try (var statement = blocker.createStatement()) {
                    statement.execute("SELECT id FROM app.notification_recipient WHERE id='" + id + "' FOR UPDATE");
                    try (var rows = statement.executeQuery("SELECT pg_backend_pid()")) { rows.next(); pid = rows.getInt(1); }
                }
                var futures = new ArrayList<Future<Integer>>();
                for (var action : List.of("read", "archive")) {
                    futures.add(executor.submit(() -> mvc.perform(put("/api/v1/inbox/" + id + "/" + action)
                            .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":0}")).andReturn().getResponse().getStatus()));
                }
                // Both have read version zero and reached their UPDATE before either can commit.
                await().atMost(10, TimeUnit.SECONDS).until(() -> jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)) OR cardinality(pg_blocking_pids(pid)) > 0 AND query ILIKE '%update%notification_recipient%'",
                        Long.class, pid) == 2L);
                blocker.commit();
                assertThat(List.of(futures.get(0).get(30, TimeUnit.SECONDS), futures.get(1).get(30, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(200, 409);
            } finally { blocker.rollback(); }
        }
        assertThat(jdbc.queryForObject("SELECT version FROM app.notification_recipient WHERE id=?", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT (read_at IS NOT NULL)::int + (archived_at IS NOT NULL)::int FROM app.notification_recipient WHERE id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test void deactivationDoesNotCancelAlreadyAuthorizedMutationButDeniesNextRequest() throws Exception {
        var id = create(); var token = reader();
        try (var blocker = jdbc.getDataSource().getConnection(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try {
                int pid;
                try (var statement = blocker.createStatement()) {
                    statement.execute("SELECT id FROM app.notification_recipient WHERE id='" + id + "' FOR UPDATE");
                    try (var rows = statement.executeQuery("SELECT pg_backend_pid()")) { rows.next(); pid = rows.getInt(1); }
                }
                var request = executor.submit(() -> mvc.perform(put("/api/v1/inbox/" + id + "/read")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}")).andReturn().getResponse().getStatus());
                await().atMost(10, TimeUnit.SECONDS).until(() -> jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))", Boolean.class, pid));
                actor.callAs("deactivation", () -> new TransactionTemplate(transactions).execute(status -> {
                    entityManager.find(Recipient.class, recipient).deactivate(); return null;
                }));
                blocker.commit();
                assertThat(request.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            } finally { blocker.rollback(); }
        }
        mvc.perform(get("/api/v1/inbox/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT version FROM app.notification_recipient WHERE id=?", Long.class, id)).isEqualTo(1L);
    }

    @Test void databaseLockTimeoutReturns503AndSameKeyCanRetry() throws Exception {
        // SET LOCAL applies only to the fixture's connection; a trigger sets the service connection timeout.
        jdbc.execute("CREATE FUNCTION app.api_lock_timeout() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM set_config('lock_timeout','200ms',true); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER api_lock_timeout BEFORE INSERT ON app.ingestion_request FOR EACH ROW EXECUTE FUNCTION app.api_lock_timeout()");
        try (var blocker = jdbc.getDataSource().getConnection()) {
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.createStatement()) { statement.execute("SELECT id FROM app.recipient WHERE id='" + recipient + "' FOR UPDATE"); }
                mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer " + producer())
                        .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(body(recipient, "Title")))
                        .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "1"))
                        .andExpect(jsonPath("$.errorId").isNotEmpty());
            } finally { blocker.rollback(); }
        } finally {
            jdbc.execute("DROP TRIGGER api_lock_timeout ON app.ingestion_request");
            jdbc.execute("DROP FUNCTION app.api_lock_timeout()");
        }
        for (var table : List.of("notification", "notification_recipient", "outbox_event", "ingestion_request")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app." + table + " WHERE tenant_id=?", Long.class, tenant)).isZero();
        }
        create();
    }

    String sign(JWTClaimsSet claims) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        jwt.sign(new RSASSASigner(KEY)); return jwt.serialize();
    }

    @Test void validatesPayloadHeadersPaginationAndForeignRecipientsWithoutWrites() throws Exception {
        var foreign = seedRecipient(UUID.randomUUID(), "foreign");
        for (var body : List.of("{", body(recipient, " "), body(foreign, "Title"), "{}")) {
            mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer " + producer())
                    .header("Idempotency-Key", "key").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        mvc.perform(post("/api/v1/notifications").header("Authorization", "Bearer " + producer())
                .contentType(MediaType.APPLICATION_JSON).content(body(recipient, "Title"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/inbox?size=101").header("Authorization", "Bearer " + reader())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/inbox/not-a-uuid").header("Authorization", "Bearer " + reader())).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.ingestion_request WHERE tenant_id=?", Long.class, tenant)).isZero();
    }
}
