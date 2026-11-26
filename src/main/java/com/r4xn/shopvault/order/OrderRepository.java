package com.r4xn.shopvault.order;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<CustomerOrder, UUID> {

    Optional<CustomerOrder> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    /** Ownership is part of the query: another user's order id simply is not found. */
    Optional<CustomerOrder> findByIdAndUserId(UUID id, UUID userId);

    Page<CustomerOrder> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<CustomerOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<CustomerOrder> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select o from CustomerOrder o where o.id = :id and o.userId = :userId")
    Optional<CustomerOrder> findByIdAndUserIdForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select o from CustomerOrder o where o.id = :id")
    Optional<CustomerOrder> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select o from CustomerOrder o where o.paymentReference = :reference")
    Optional<CustomerOrder> findByPaymentReferenceForUpdate(@Param("reference") String reference);

    @Query("select o.id from CustomerOrder o where o.status = :status and o.createdAt < :cutoff order by o.createdAt")
    List<UUID> findIdsCreatedBefore(
            @Param("status") OrderStatus status, @Param("cutoff") Instant cutoff, Pageable pageable);
}
