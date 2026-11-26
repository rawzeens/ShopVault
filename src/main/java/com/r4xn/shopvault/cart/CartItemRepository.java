package com.r4xn.shopvault.cart;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    @Query("select c from CartItem c join fetch c.product where c.userId = :userId order by c.createdAt, c.id")
    List<CartItem> findWithProducts(@Param("userId") UUID userId);

    /** Product ids and quantities only, so checkout reads product rows fresh under a lock. */
    @Query("select new com.r4xn.shopvault.cart.CartLine(c.product.id, c.quantity) from CartItem c "
            + "where c.userId = :userId")
    List<CartLine> findLines(@Param("userId") UUID userId);

    Optional<CartItem> findByUserIdAndProductId(UUID userId, UUID productId);

    long countByUserId(UUID userId);

    @Modifying
    @Query("delete from CartItem c where c.userId = :userId")
    int deleteAllForUser(@Param("userId") UUID userId);

    @Modifying
    @Query("delete from CartItem c where c.userId = :userId and c.product.id = :productId")
    int deleteLine(@Param("userId") UUID userId, @Param("productId") UUID productId);
}
