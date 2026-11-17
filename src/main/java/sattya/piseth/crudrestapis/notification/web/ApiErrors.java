package sattya.piseth.crudrestapis.notification.web;

import jakarta.persistence.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import sattya.piseth.crudrestapis.notification.application.IdempotencyConflictException;
import java.sql.SQLException;
import java.util.*;

@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotency() { return problem(HttpStatus.CONFLICT, "Idempotency key was reused with a different payload"); }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid() { return problem(HttpStatus.BAD_REQUEST, "Invalid notification request or recipients"); }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail denied() { return problem(HttpStatus.FORBIDDEN, "Access denied"); }

    /** Handles native JPA, translated Spring, and transaction-boundary wrappers consistently. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception) {
        var causes = new ArrayList<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = exception; cause != null && seen.add(cause) && causes.size() < 32; cause = cause.getCause()) {
            causes.add(cause);
        }
        if (causes.stream().anyMatch(cause -> cause instanceof OptimisticLockException || cause instanceof OptimisticLockingFailureException)) {
            return ResponseEntity.status(409).body(problem(HttpStatus.CONFLICT, "Concurrent update; reload the inbox item and retry"));
        }
        var sqlState = causes.stream().filter(SQLException.class::isInstance).map(SQLException.class::cast)
                .map(SQLException::getSQLState).filter(Objects::nonNull).filter(state -> state.matches("[A-Z0-9]{5}"))
                .findFirst().orElse("none");
        // SQLSTATE takes precedence over broad wrapper types: constraint/auth/config errors are not transient.
        boolean retryable = !sqlState.equals("none") ? retryableSqlState(sqlState)
                : causes.stream().anyMatch(cause -> cause instanceof TransientDataAccessException
                        || cause instanceof DataAccessResourceFailureException
                        || cause instanceof jakarta.persistence.LockTimeoutException
                        || cause instanceof jakarta.persistence.QueryTimeoutException
                        || cause instanceof PessimisticLockException);
        var id = UUID.randomUUID().toString();
        // Never log exception messages, causes, SQL parameters, JWTs, or database row detail.
        var category = exception == null ? "unknown" : exception.getClass().getSimpleName();
        logger.error("API failure id=" + id + " category=" + category + " sqlState=" + sqlState);
        var body = problem(retryable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR,
                retryable ? "Database temporarily unavailable; retry with the same idempotency key"
                        : "An unexpected server error occurred");
        body.setProperty("errorId", id);
        var response = ResponseEntity.status(body.getStatus()).header("X-Correlation-ID", id);
        if (retryable) response.header("Retry-After", "1");
        return response.body(body);
    }

    private boolean retryableSqlState(String state) {
        return state.startsWith("08") || Set.of("40001", "40P01", "55P03", "57014", "57P01", "57P02", "57P03").contains(state);
    }

    private ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
