package sattya.piseth.crudrestapis.infrastructure.migration;

import org.flywaydb.core.Flyway;

/** Runs separately from the application so runtime credentials remain CRUD-only. */
public final class DatabaseMigrator {
    private DatabaseMigrator() {}

    public static void main() {
        Flyway.configure()
                .dataSource(required("MIGRATION_DB_URL"), required("MIGRATION_DB_USERNAME"),
                        required("MIGRATION_DB_PASSWORD"))
                .schemas("app")
                .defaultSchema("app")
                .createSchemas(false)
                .initSql("SET ROLE crud_owner")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be supplied");
        }
        return value;
    }
}
