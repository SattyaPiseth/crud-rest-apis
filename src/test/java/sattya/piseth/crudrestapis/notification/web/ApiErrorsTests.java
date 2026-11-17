package sattya.piseth.crudrestapis.notification.web;

import jakarta.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;

class ApiErrorsTests {
    private final ApiErrors errors = new ApiErrors();

    @Test void recognizesNativeAndWrappedTransientFailures() {
        for (var exception : new Exception[]{new LockTimeoutException("private"), new QueryTimeoutException("private"),
                new CannotCreateTransactionException("private", new SQLException("private", "08001")),
                new PersistenceException("private", new SQLException("private", "40P01"))}) {
            var response = errors.unexpected(exception);
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
            assertThat(response.getBody().getDetail()).doesNotContain("private");
            assertThat(response.getHeaders().getFirst("X-Correlation-ID"))
                    .isEqualTo(response.getBody().getProperties().get("errorId"));
        }
    }

    @Test void permanentSqlFailuresAndUnknownTransactionFailuresAreNotRetryable() {
        for (var state : new String[]{"23503", "23514", "28P01", "42501", "42P01"}) {
            var response = errors.unexpected(new DataAccessResourceFailureException("private", new SQLException("private", state)));
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getHeaders().getFirst("Retry-After")).isNull();
        }
        assertThat(errors.unexpected(new CannotCreateTransactionException("unknown")).getStatusCode().value()).isEqualTo(500);
    }

    @Test void wrappedOptimisticFailuresAreConflicts() {
        assertThat(errors.unexpected(new RollbackException("private", new OptimisticLockException("private")))
                .getStatusCode().value()).isEqualTo(409);
    }
}
