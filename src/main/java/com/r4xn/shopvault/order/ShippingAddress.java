package com.r4xn.shopvault.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record ShippingAddress(
        @Column(name = "ship_name", nullable = false, length = 100) String recipientName,
        @Column(name = "ship_phone", nullable = false, length = 20) String phone,
        @Column(name = "ship_line1", nullable = false, length = 200) String line1,
        @Column(name = "ship_line2", length = 200) String line2,
        @Column(name = "ship_city", nullable = false, length = 100) String city,
        @Column(name = "ship_state", nullable = false, length = 100) String state,
        @Column(name = "ship_postcode", nullable = false, length = 10) String postcode,
        @Column(name = "ship_country", nullable = false, length = 2) String country) {}
