package sattya.piseth.crudrestapis.config;

import java.net.URI;

public final class JwkSetUriValidator {
    private static final String HTTP_POLICY = "app.api.jwk-set-uri must use HTTPS, or HTTP only with host 127.0.0.1, [::1], or localhost";

    private JwkSetUriValidator() {}

    public static URI validate(String value) {
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException _) {
            throw new IllegalArgumentException(HTTP_POLICY);
        }

        var scheme = uri.getScheme();
        var host = uri.getHost();
        if (!uri.isAbsolute() || uri.isOpaque() || host == null || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException(HTTP_POLICY);
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return uri;
        }
        var loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)
                || "[::1]".equalsIgnoreCase(host) || "::1".equalsIgnoreCase(host);
        if ("http".equalsIgnoreCase(scheme) && loopback) {
            return uri;
        }
        throw new IllegalArgumentException(HTTP_POLICY);
    }
}
