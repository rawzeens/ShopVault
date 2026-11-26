package com.r4xn.shopvault.audit;

import com.r4xn.shopvault.common.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuditController {

    private final AuditRepository repository;

    public AdminAuditController(AuditRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public PageResponse<AuditEntry> list(
            @RequestParam(required = false) @Pattern(regexp = "^[A-Z_]{1,50}$") String action,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        PageRequest pageable = PageRequest.of(page, size);
        var result = action == null
                ? repository.findAllByOrderByOccurredAtDesc(pageable)
                : repository.findByActionOrderByOccurredAtDesc(action, pageable);
        return PageResponse.of(result, AuditEntry::from);
    }

    public record AuditEntry(
            UUID id, Instant occurredAt, UUID actorId, String action, String target, String clientIp, String detail) {

        static AuditEntry from(AuditEvent e) {
            return new AuditEntry(e.getId(), e.getOccurredAt(), e.getActorId(), e.getAction(), e.getTarget(),
                    e.getClientIp(), e.getDetail());
        }
    }
}
