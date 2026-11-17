package sattya.piseth.crudrestapis.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Validate before conditional security chains are instantiated: typos must fail closed. */
@Configuration(proxyBeanMethods = false)
public class ApiSettingsConfiguration {
    private ApiSettingsConfiguration() {}

    @Bean
    static BeanFactoryPostProcessor validateApiSettings(Environment environment) {
        return beanFactory -> {
            String enabled = environment.getProperty("app.api.enabled", "false");
            if (!enabled.equalsIgnoreCase("true") && !enabled.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("app.api.enabled must be true or false");
            }
            if (Boolean.parseBoolean(enabled)) {
                for (var name : new String[]{"app.api.issuer", "app.api.jwk-set-uri", "app.api.audience"}) {
                    var value = environment.getProperty(name);
                    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
                }
                JwkSetUriValidator.validate(environment.getProperty("app.api.jwk-set-uri"));
            }
        };
    }
}
