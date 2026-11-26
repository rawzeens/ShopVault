package com.r4xn.shopvault.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findAllByOrderByOccurredAtDesc(Pageable pageable);

    Page<AuditEvent> findByActionOrderByOccurredAtDesc(String action, Pageable pageable);

    List<AuditEvent> findByTargetOrderByOccurredAtAsc(String target);
}
