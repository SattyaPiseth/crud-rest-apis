package sattya.piseth.crudrestapis.outbox.publication;

import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Private JDBC transaction manager: never replaces the application's JPA manager. */
public final class OutboxStore {
    public static final String ACTOR = "service:outbox-publisher";

    private static final String SELECT_ELIGIBLE_SQL = """
            SELECT id, aggregate_id, event_type, schema_version, payload, attempt_count
            FROM app.outbox_event WHERE tenant_id = ? AND (
                state = 'PENDING' OR (state = 'RETRY' AND next_attempt_at <= statement_timestamp())
                OR (state = 'PROCESSING' AND lease_until <= statement_timestamp()))
            ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED
            """;
    private static final String FAIL_EXHAUSTED_SQL = """
            UPDATE app.outbox_event SET state='FAILED', failure_code='EXHAUSTED',
                claim_token=NULL, lease_until=NULL, next_attempt_at=NULL,
                updated_at=clock_timestamp(), updated_by=?, version=version+1
            WHERE tenant_id=? AND id=?
            """;
    private static final String CLAIM_EVENT_SQL = """
            UPDATE app.outbox_event SET state='PROCESSING', attempt_count=attempt_count+1,
                claim_token=?, lease_until=clock_timestamp() + (? * interval '1 millisecond'),
                next_attempt_at=NULL, failure_code=NULL,
                updated_at=clock_timestamp(), updated_by=?, version=version+1
            WHERE tenant_id=? AND id=?
            """;
    private static final String LOCK_CLAIM_SQL = """
            SELECT attempt_count FROM app.outbox_event
            WHERE tenant_id=? AND id=? AND state='PROCESSING' AND claim_token=? FOR UPDATE
            """;
    private static final String FINALIZE_CLAIM_SQL = """
            UPDATE app.outbox_event SET state=?, failure_code=?, claim_token=NULL, lease_until=NULL,
                next_attempt_at=CASE WHEN ?='RETRY' THEN clock_timestamp() + (? * interval '1 millisecond') ELSE NULL END,
                published_at=CASE WHEN ?='PUBLISHED' THEN clock_timestamp() ELSE NULL END,
                updated_at=clock_timestamp(), updated_by=?, version=version+1
            WHERE tenant_id=? AND id=? AND state='PROCESSING' AND claim_token=?
                AND lease_until > clock_timestamp()
            """;
    private static final String COUNT_EXPIRED_SQL = """
            SELECT count(*) FROM app.outbox_event WHERE tenant_id=?
            AND state='PROCESSING' AND lease_until <= statement_timestamp()
            """;
    private static final String OLDEST_UNPUBLISHED_SQL = """
            SELECT COALESCE(GREATEST(0, EXTRACT(EPOCH FROM statement_timestamp() - min(created_at))),0)
            FROM app.outbox_event WHERE tenant_id=? AND state <> 'PUBLISHED'
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Set<UUID> tenants;
    private final OutboxSettings settings;

    public record Claim(Publication event, UUID token, int attempt) {}
    public record Backlog(Map<String, Long> states, long expiredLeases, double oldestSeconds) {}

    public OutboxStore(DataSource dataSource, OutboxSettings settings) {
        this.settings = settings;
        tenants = Set.copyOf(settings.tenants());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(5);
    }

    public List<Claim> claim(UUID tenant, int limit) {
        if (limit < 1 || limit > settings.batchSize()) throw new IllegalArgumentException("Invalid claim limit");
        return inTenant(tenant, () -> {
            var rows = jdbc.queryForList(SELECT_ELIGIBLE_SQL, tenant, limit);
            var claims = new ArrayList<Claim>();
            for (var row : rows) {
                var id = (UUID) row.get("id");
                int attempt = ((Number) row.get("attempt_count")).intValue();
                if (attempt >= settings.maxAttempts()) {
                    jdbc.update(FAIL_EXHAUSTED_SQL, ACTOR, tenant, id);
                    continue;
                }
                var token = UUID.randomUUID();
                jdbc.update(CLAIM_EVENT_SQL, token, settings.leaseDuration().toMillis(), ACTOR, tenant, id);
                claims.add(new Claim(new Publication(id, tenant, (UUID) row.get("aggregate_id"),
                        (String) row.get("event_type"), ((Number) row.get("schema_version")).intValue(),
                        (String) row.get("payload")), token, attempt + 1));
            }
            return List.copyOf(claims);
        });
    }

    public boolean finish(Claim claim, PublicationOutcome outcome, Duration retryDelay) {
        Objects.requireNonNull(outcome, "outcome");
        if (retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()
                || retryDelay.compareTo(settings.maxRetry()) > 0)
            throw new IllegalArgumentException("Retry delay outside configured bounds");
        return inTenant(claim.event().tenantId(), () -> {
            // Lock first so the expiry predicate is evaluated AFTER any lock wait.
            // The persisted attempt count, not caller-supplied metadata, controls exhaustion.
            var attempts = jdbc.queryForList(LOCK_CLAIM_SQL, Integer.class, claim.event().tenantId(), claim.event().eventId(), claim.token());
            if (attempts.isEmpty()) return false;
            String state = target(outcome, attempts.getFirst());
            String failure = outcome == PublicationOutcome.ACKNOWLEDGED ? null : outcome.name();
            if (state.equals("FAILED") && outcome != PublicationOutcome.PERMANENT) failure = "EXHAUSTED";
            return jdbc.update(FINALIZE_CLAIM_SQL, state, failure, state, retryDelay.toMillis(), state, ACTOR,
                    claim.event().tenantId(), claim.event().eventId(), claim.token()) == 1;
        });
    }

    private String target(PublicationOutcome outcome, int attempts) {
        if (outcome == PublicationOutcome.ACKNOWLEDGED) return "PUBLISHED";
        if (outcome == PublicationOutcome.PERMANENT || attempts >= settings.maxAttempts()) return "FAILED";
        return "RETRY";
    }

    public Backlog backlog(UUID tenant) {
        return inTenant(tenant, () -> {
            var counts = new HashMap<String, Long>();
            RowCallbackHandler collectCount = rs -> counts.put(rs.getString(1), rs.getLong(2));
            jdbc.query("SELECT state, count(*) FROM app.outbox_event WHERE tenant_id=? GROUP BY state",
                    collectCount, tenant);
            long expired = Objects.requireNonNull(jdbc.queryForObject(COUNT_EXPIRED_SQL, Long.class, tenant));
            double oldest = Objects.requireNonNull(jdbc.queryForObject(OLDEST_UNPUBLISHED_SQL, Double.class, tenant));
            return new Backlog(Map.copyOf(counts), expired, oldest);
        });
    }

    private <T> T inTenant(UUID tenant, Supplier<T> action) {
        if (!tenants.contains(tenant)) throw new IllegalArgumentException("Tenant is not authorized for this worker");
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            return action.get();
        });
    }
}
