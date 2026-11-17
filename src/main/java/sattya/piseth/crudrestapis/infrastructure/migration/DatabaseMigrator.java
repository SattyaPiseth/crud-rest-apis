package sattya.piseth.crudrestapis.infrastructure.migration;

import org.flywaydb.core.Flyway;
import java.util.Map;

/** Runs separately from the application so runtime credentials remain CRUD-only. */
public final class DatabaseMigrator {
    private DatabaseMigrator() {}

    public static void main() {
        migrate(System.getenv());
    }

    static void migrate(Map<String, String> environment) {
        Flyway.configure()
                .dataSource(required(environment, "MIGRATION_DB_URL"),
                        required(environment, "MIGRATION_DB_USERNAME"),
                        required(environment, "MIGRATION_DB_PASSWORD"))
                .schemas("app")
                .defaultSchema("app")
                .createSchemas(false)
                .initSql("SET ROLE crud_owner")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static String required(Map<String, String> environment, String name) {
        var value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be supplied");
        }
        return value;
    }
}
