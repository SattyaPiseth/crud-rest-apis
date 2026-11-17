package sattya.piseth.crudrestapis.infrastructure.migration;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DatabaseMigratorTests {
    @Test
    void rejectsEachMissingSettingBeforeConnectingToTheDatabase() {
        for (var name : List.of("MIGRATION_DB_URL", "MIGRATION_DB_USERNAME", "MIGRATION_DB_PASSWORD")) {
            var environment = validSettings();
            environment.remove(name);
            assertThatIllegalStateException().isThrownBy(() -> DatabaseMigrator.migrate(environment))
                    .withMessage(name + " must be supplied");
        }
    }

    @Test
    void rejectsEachBlankSettingBeforeConnectingToTheDatabase() {
        for (var name : List.of("MIGRATION_DB_URL", "MIGRATION_DB_USERNAME", "MIGRATION_DB_PASSWORD")) {
            var environment = validSettings();
            environment.put(name, " \t ");
            assertThatIllegalStateException().isThrownBy(() -> DatabaseMigrator.migrate(environment))
                    .withMessage(name + " must be supplied");
        }
    }

    private Map<String, String> validSettings() {
        // Validation must fail before any connection to this unused address is attempted.
        return new HashMap<>(Map.of(
                "MIGRATION_DB_URL", "jdbc:postgresql://127.0.0.1:1/unused",
                "MIGRATION_DB_USERNAME", "test",
                "MIGRATION_DB_PASSWORD", "test"));
    }
}
