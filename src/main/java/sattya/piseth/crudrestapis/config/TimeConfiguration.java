package sattya.piseth.crudrestapis.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {
    public static final ZoneId APPLICATION_ZONE = ZoneId.of("Asia/Phnom_Penh");

    @Bean
    Clock clock() {
        return Clock.system(APPLICATION_ZONE);
    }

    @Bean
    JsonMapperBuilderCustomizer applicationTimestamps() {
        var formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(APPLICATION_ZONE);
        // ValueSerializer is an abstract class, so only the builder callback can be a lambda.
        return builder -> builder.addModule(new SimpleModule("application-timestamps")
                .addSerializer(Instant.class, new ValueSerializer<Instant>() {
                    @Override
                    public void serialize(Instant value, JsonGenerator generator, SerializationContext context) {
                        generator.writeString(formatter.format(value));
                    }
                }));
    }
}
