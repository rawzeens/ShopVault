package com.r4xn.shopvault.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Security-relevant events (logins, lockouts, token reuse, admin changes, payments) go to the
 * {@code audit_log} table and to a dedicated "AUDIT" logger that can be shipped to a SIEM.
 * Values are stripped of control characters so they cannot forge log lines.
 */
@Service
public class AuditService {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    private final AuditRepository repository;
    private final Clock clock;

    public AuditService(AuditRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void record(UUID actorId, String action, String target, String detail) {
        String ip = currentClientIp();
        String safeTarget = clean(target, 200);
        String safeDetail = clean(detail, 500);
        repository.save(new AuditEvent(clock.instant(), actorId, action, safeTarget, ip, safeDetail));
        AUDIT.info("action={} actor={} target={} ip={} detail={}", action, actorId, safeTarget, ip, safeDetail);
    }

    static String clean(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", "_");
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }

    private static String currentClientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            return clean(request.getRemoteAddr(), 45);
        }
        return null;
    }
}
