package sattya.piseth.crudrestapis.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.api.enabled", havingValue = "true")
public class ApiSecurityConfiguration {
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.api.issuer}") String issuer,
                          @Value("${app.api.jwk-set-uri}") String jwks,
                          @Value("${app.api.audience}") String audience) {
        if (issuer.isBlank() || issuer.length() > 300 || jwks.isBlank() || audience.isBlank()) {
            throw new IllegalArgumentException("API issuer, JWK URL and audience are required");
        }
        var jwkSetUri = JwkSetUriValidator.validate(jwks).toString();
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3))
                .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        var restOperations = new RestTemplate(requestFactory);
        var defaultErrors = new DefaultResponseErrorHandler();
        restOperations.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return response.getStatusCode().is3xxRedirection() || defaultErrors.hasError(response);
            }

            @Override
            public void handleError(URI url, org.springframework.http.HttpMethod method, ClientHttpResponse response)
                    throws IOException {
                if (response.getStatusCode().is3xxRedirection()) {
                    throw new IOException("JWK Set endpoint redirects are not allowed");
                }
                defaultErrors.handleError(url, method, response);
            }
        });
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(restOperations)
                .build();
        var converter = MappedJwtClaimSetConverter.withDefaults(java.util.Map.of());
        decoder.setClaimSetConverter(claims -> {
            if (!(claims.get("sub") instanceof String)) throw new BadJwtException("Invalid subject claim");
            return converter.convert(claims);
        });
        decoder.setJwtValidator(validators(issuer, audience));
        return token -> {
            // Inspect raw JSON types before Nimbus/Spring can coerce identity claims to strings.
            // Nothing is trusted or used for authorization until decoder.decode verifies the signature.
            try {
                var raw = com.nimbusds.jwt.SignedJWT.parse(token).getPayload().toJSONObject();
                if (raw == null || !(raw.get("sub") instanceof String) || !(raw.get("tenant_id") instanceof String)
                        || raw.containsKey("producer_id") && !(raw.get("producer_id") instanceof String)
                        || !validScopes(raw.get("scope")) || !validScopes(raw.get("scp"))) {
                    throw new BadJwtException("Invalid claim types");
                }
            } catch (java.text.ParseException _) {
                throw new BadJwtException("Malformed token");
            }
            return decoder.decode(token);
        };
    }

    public static OAuth2TokenValidator<Jwt> validators(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            try {
                var rawTenant = jwt.getClaims().get("tenant_id");
                boolean valid = rawTenant instanceof String tenant && UUID.fromString(tenant).toString().equals(tenant)
                        && jwt.getSubject() != null && !jwt.getSubject().isBlank() && jwt.getSubject().length() <= 180
                        && jwt.getExpiresAt() != null && jwt.getAudience().contains(audience)
                        && validScopes(jwt.getClaims().get("scope")) && validScopes(jwt.getClaims().get("scp"));
                if (valid) return OAuth2TokenValidatorResult.success();
            } catch (RuntimeException _) {
                // Malformed claim types are authentication failures, not application errors.
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid required claims", null));
        };
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), claims);
    }

    private static boolean validScopes(Object value) {
        return value == null || value instanceof String
                || value instanceof java.util.Collection<?> scopes && scopes.stream().allMatch(String.class::isInstance);
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
            @Value("${app.api.role-source-client:notification-api}") String roleSourceClient) {
        var entry = bearerEntryPoint();
        var denied = bearerDeniedHandler();
        return http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Bearer credentials are explicitly supplied, not ambient browser credentials.
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers(ApiSecurityConfiguration::isBearerApiWriteRequest)
                        .withObjectPostProcessor(new ObjectPostProcessor<CsrfFilter>() {
                            @Override
                            public <O extends CsrfFilter> O postProcess(O filter) {
                                filter.setAccessDeniedHandler(denied);
                                return filter;
                            }
                        }))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/notifications").hasAuthority("SCOPE_notifications:write")
                        .requestMatchers(HttpMethod.GET, "/api/v1/inbox", "/api/v1/inbox/*").hasAuthority("SCOPE_inbox:read")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/inbox/*/read", "/api/v1/inbox/*/archive").hasAuthority("SCOPE_inbox:write")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(entry)
                        .accessDeniedHandler(denied))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter(roleSourceClient)))
                        .authenticationEntryPoint(entry)
                        .accessDeniedHandler(denied))
                .addFilterAfter(new ApiRequestSizeLimitFilter(), AuthorizationFilter.class)
                .build();
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter(String roleSourceClient) {
        var scopeConverter = new JwtGrantedAuthoritiesConverter();
        var authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>(scopeConverter.convert(jwt));
            Object resourceAccessClaim = jwt.getClaims().get("resource_access");
            if (resourceAccessClaim instanceof Map<?, ?> resources
                    && resources.get(roleSourceClient) instanceof Map<?, ?> apiClient
                    && apiClient.get("roles") instanceof Collection<?> roles) {
                roles.stream().filter(String.class::isInstance).map(String.class::cast)
                        .map(role -> new SimpleGrantedAuthority("SCOPE_" + role))
                        .forEach(authorities::add);
            }
            return authorities;
        });
        return authenticationConverter;
    }

    static boolean isApiWriteRequest(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getServletPath();
        return method.equals("POST") && path.equals("/api/v1/notifications")
                || method.equals("PUT") && path.matches("/api/v1/inbox/[^/]+/(read|archive)");
    }

    static boolean isBearerApiWriteRequest(HttpServletRequest request) {
        if (!isApiWriteRequest(request)) return false;

        String authorization = request.getHeader("Authorization");
        if (authorization == null) return false;
        int separator = authorization.indexOf(' ');
        return separator > 0 && separator < authorization.length() - 1
                && authorization.substring(0, separator).equalsIgnoreCase("Bearer");
    }

    static AuthenticationEntryPoint bearerEntryPoint() {
        var delegate = new BearerTokenAuthenticationEntryPoint();
        return (request, response, exception) -> {
            delegate.commence(request, response, exception);
            problem(response);
        };
    }

    static AccessDeniedHandler bearerDeniedHandler() {
        var delegate = new BearerTokenAccessDeniedHandler();
        return (request, response, exception) -> {
            delegate.handle(request, response, exception);
            problem(response);
        };
    }

    private static void problem(HttpServletResponse response) throws IOException {
        int status = response.getStatus();
        response.setContentType("application/problem+json");
        String title = HttpStatus.valueOf(status).getReasonPhrase();
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status + "}");
    }
}
