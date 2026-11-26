package com.r4xn.shopvault.cart;

import java.util.UUID;

public record CartLine(UUID productId, int quantity) {}
