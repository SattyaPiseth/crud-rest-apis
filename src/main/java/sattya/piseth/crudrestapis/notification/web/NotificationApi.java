package sattya.piseth.crudrestapis.notification.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import sattya.piseth.crudrestapis.notification.application.*;
import sattya.piseth.crudrestapis.inbox.application.InboxService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.api.enabled", havingValue = "true")
public class NotificationApi {
    private final NotificationIngestionService ingestion;
    private final InboxService inbox;

    public record Create(@NotBlank @Size(max=100) String type, @NotBlank @Size(max=100) String category,
                         @NotBlank @Size(max=200) String title, @NotBlank @Size(max=10000) String body,
                         Instant expiresAt, @NotNull @Size(min=1,max=1000) List<@NotNull UUID> recipientIds) {}
    public record Version(@NotNull @PositiveOrZero Long version) {}

    @PostMapping("/notifications")
    public ResponseEntity<IngestionResult> create(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max=200) String key,
            @Valid @RequestBody Create request) {
        var rawProducer = jwt.getClaims().get("client_id");
        if (!(rawProducer instanceof String producer) || producer.isBlank() || producer.length() > 100) {
            throw new AccessDeniedException("A registered producer identity is required");
        }
        var result = ingestion.ingest(new ProducerScope(UUID.fromString(jwt.getClaimAsString("tenant_id")), producer),
                key, new IngestNotification(request.type(), request.category(), request.title(), request.body(), request.expiresAt(), request.recipientIds()));
        return ResponseEntity.status(result.replayed() ? 200 : 201).body(result);
    }

    @GetMapping("/inbox")
    public InboxService.Page list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return inbox.list(owner(jwt), page, size);
    }

    @GetMapping("/inbox/{id}")
    public InboxService.Item get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return inbox.get(owner(jwt), id);
    }

    @PutMapping("/inbox/{id}/read")
    public InboxService.Mutation read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Version request) {
        return inbox.update(owner(jwt), id, request.version(), false);
    }

    @PutMapping("/inbox/{id}/archive")
    public InboxService.Mutation archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Version request) {
        return inbox.update(owner(jwt), id, request.version(), true);
    }

    private InboxService.Owner owner(Jwt jwt) {
        return new InboxService.Owner(UUID.fromString(jwt.getClaimAsString("tenant_id")), jwt.getClaimAsString("iss"), jwt.getSubject());
    }
}
