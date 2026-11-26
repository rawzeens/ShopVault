package com.r4xn.shopvault.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(length = 200)
    private String target;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(length = 500)
    private String detail;

    protected AuditEvent() {}

    AuditEvent(Instant occurredAt, UUID actorId, String action, String target, String clientIp, String detail) {
        this.occurredAt = occurredAt;
        this.actorId = actorId;
        this.action = action;
        this.target = target;
        this.clientIp = clientIp;
        this.detail = detail;
    }

    public UUID getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getTarget() {
        return target;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getDetail() {
        return detail;
    }
}
