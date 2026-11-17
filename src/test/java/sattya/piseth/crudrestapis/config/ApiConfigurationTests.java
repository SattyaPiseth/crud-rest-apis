package sattya.piseth.crudrestapis.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class ApiConfigurationTests {
    private final ApplicationContextRunner settings = new ApplicationContextRunner().withUserConfiguration(ApiSettingsConfiguration.class);

    @Test void invalidEnablementCannotFallThroughToDefaultSecurity() {
        settings.withPropertyValues("app.api.enabled=yes").run(context ->
                assertThat(context.getStartupFailure()).hasMessageContaining("app.api.enabled must be true or false"));
    }

    @Test void enabledApiRequiresEverySetting() {
        var names = new String[]{"app.api.issuer", "app.api.jwk-set-uri", "app.api.audience"};
        for (var missing : names) {
            var runner = settings.withPropertyValues("app.api.enabled=true");
            for (var name : names) if (!name.equals(missing)) runner = runner.withPropertyValues(name + "=https://example.test");
            runner.run(context -> assertThat(context.getStartupFailure()).hasMessageContaining(missing + " is required"));
        }
        settings.withPropertyValues("app.api.enabled=true", "app.api.issuer= ", "app.api.jwk-set-uri=https://example.test", "app.api.audience=api")
                .run(context -> assertThat(context.getStartupFailure()).hasMessageContaining("app.api.issuer is required"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://identity.example/jwks",
            "https://10.20.30.40:8443/keys",
            "https://identity.svc.cluster.local/jwks",
            "http://127.0.0.1:8080/jwks",
            "http://[::1]:8080/jwks",
            "http://localhost:8080/jwks"
    })
    void acceptsHttpsAndExplicitLoopbackJwkUris(String value) {
        assertThat(JwkSetUriValidator.validate(value)).hasToString(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://8.8.8.8/jwks",
            "http://10.0.0.1/jwks",
            "http://192.168.1.2/jwks",
            "http://identity.example/jwks",
            "http://jwks.default.svc.cluster.local/jwks",
            "file:///tmp/jwks.json",
            "ftp://identity.example/jwks",
            "https://user:password@identity.example/jwks",
            "https://identity.example/jwks#fragment",
            "https://identity.example:99999/jwks",
            "https://",
            "/relative/jwks",
            "https://identity example/jwks"
    })
    void rejectsInsecureOrMalformedJwkUris(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> JwkSetUriValidator.validate(value))
                .withMessageContaining("app.api.jwk-set-uri");
    }

    @Test void enabledSettingsFailStartupForRemoteHttpJwkUriWithoutEchoingTheUri() {
        settings.withPropertyValues("app.api.enabled=true", "app.api.issuer=https://identity.example",
                "app.api.jwk-set-uri=http://private.internal/jwks", "app.api.audience=api")
                .run(context -> assertThat(context.getStartupFailure().toString())
                        .contains("app.api.jwk-set-uri").doesNotContain("private.internal"));
    }

    @Test void disabledOrMissingFlagDeniesRequestsWithoutJwtSettings() {
        for (var properties : new String[][]{ {}, {"app.api.enabled=false"} }) {
            new WebApplicationContextRunner().withUserConfiguration(Disabled.class).withPropertyValues(properties).run(context -> {
                assertThat(context).hasNotFailed();
                var healthRequest = new MockHttpServletRequest("GET", "/actuator/health/readiness");
                healthRequest.setServletPath("/actuator/health/readiness");
                var healthResponse = new MockHttpServletResponse();
                var healthReached = new java.util.concurrent.atomic.AtomicBoolean();
                context.getBean(FilterChainProxy.class).doFilter(healthRequest, healthResponse,
                        (req, res) -> healthReached.set(true));
                assertThat(healthReached).isTrue();

                var request = new MockHttpServletRequest("GET", "/api/v1/inbox");
                request.setServletPath("/api/v1/inbox");
                var response = new MockHttpServletResponse();
                context.getBean(FilterChainProxy.class).doFilter(request, response, (req, res) -> fail("Disabled API reached application"));
                assertThat(response.getStatus()).isIn(401, 403);
            });
        }
    }

    @Test void bearerEntryPointPreservesProtocolErrorStatusAndChallenge() throws Exception {
        var response = new MockHttpServletResponse();
        var error = new org.springframework.security.oauth2.server.resource.BearerTokenError(
                "invalid_request", org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid bearer request", null);
        ApiSecurityConfiguration.bearerEntryPoint().commence(new MockHttpServletRequest(), response,
                new org.springframework.security.oauth2.core.OAuth2AuthenticationException(error));
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getHeader("WWW-Authenticate")).contains("invalid_request");
        assertThat(response.getContentAsString()).contains("\"status\":400");
    }

    @Test void csrfBearerExemptionMatchesOnlyConfiguredWriteRoutes() {
        var notification = request("POST", "/api/v1/notifications", "Bearer value");
        assertThat(ApiSecurityConfiguration.isBearerApiWriteRequest(notification)).isTrue();
        assertThat(ApiSecurityConfiguration.isBearerApiWriteRequest(
                request("POST", "/api/v1/unconfigured", "Bearer value"))).isFalse();
        assertThat(ApiSecurityConfiguration.isBearerApiWriteRequest(
                request("GET", "/api/v1/inbox", "Bearer value"))).isFalse();
        assertThat(ApiSecurityConfiguration.isBearerApiWriteRequest(
                request("PUT", "/api/v1/inbox/123/read", "Bearer value"))).isTrue();
        assertThat(ApiSecurityConfiguration.isBearerApiWriteRequest(
                request("PUT", "/api/v1/inbox/123/archive", "Bearer value"))).isTrue();
        assertThat(ApiSecurityConfiguration.isBearerApiWriteRequest(
                request("PUT", "/api/v1/inbox/123/delete", "Bearer value"))).isFalse();
        assertThat(ApiSecurityConfiguration.isBearerApiWriteRequest(
                request("POST", "/api/v1/notifications", null))).isFalse();
    }

    private static MockHttpServletRequest request(String method, String path, String authorization) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        if (authorization != null) request.addHeader("Authorization", authorization);
        return request;
    }

    @Test void requestBodyLimitRejectsOversizeStreamsWithMissingOrUnderreportedLength() throws Exception {
        for (var size : new int[]{ApiRequestSizeLimitFilter.MAX_REQUEST_BYTES + 1,
                512 * 1024, 1024 * 1024}) {
            for (var declaredLength : new int[]{-1, 0, size - 1}) {
                var request = requestWithContentLength(new byte[size], declaredLength);
                var response = new MockHttpServletResponse();
                var applicationReached = new java.util.concurrent.atomic.AtomicBoolean();

                new ApiRequestSizeLimitFilter().doFilter(request, response,
                        (servletRequest, servletResponse) -> applicationReached.set(true));

                assertThat(response.getStatus()).isEqualTo(413);
                assertThat(response.getContentType()).isEqualTo("application/problem+json");
                assertThat(response.getContentAsString()).contains("\"status\":413");
                assertThat(applicationReached).isFalse();
            }
        }
    }

    @Test void requestBodyLimitPassesCompleteBodiesAtAndBelowTheBoundary() throws Exception {
        for (var size : new int[]{0, 1024, 128 * 1024, ApiRequestSizeLimitFilter.MAX_REQUEST_BYTES}) {
            var body = new byte[size];
            java.util.Arrays.fill(body, (byte) 'x');
            var request = requestWithContentLength(body, -1);
            var response = new MockHttpServletResponse();
            var received = new java.util.concurrent.atomic.AtomicReference<byte[]>();

            new ApiRequestSizeLimitFilter().doFilter(request, response,
                    (servletRequest, servletResponse) -> received.set(servletRequest.getInputStream().readAllBytes()));

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(received.get()).containsExactly(body);
        }
    }

    private static MockHttpServletRequest requestWithContentLength(byte[] body, int declaredLength) {
        var request = new MockHttpServletRequest("POST", "/api/v1/notifications") {
            @Override public int getContentLength() { return declaredLength; }
            @Override public long getContentLengthLong() { return declaredLength; }
        };
        request.setContent(body);
        return request;
    }

    @Configuration(proxyBeanMethods=false)
    @EnableWebSecurity
    @Import({SecurityConfiguration.class, ApiSettingsConfiguration.class})
    static class Disabled {}
}
