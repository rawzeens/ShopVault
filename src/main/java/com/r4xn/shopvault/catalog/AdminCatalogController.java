package com.r4xn.shopvault.catalog;

import com.r4xn.shopvault.catalog.CatalogDtos.AdminProductResponse;
import com.r4xn.shopvault.catalog.CatalogDtos.CategoryRequest;
import com.r4xn.shopvault.catalog.CatalogDtos.CategoryResponse;
import com.r4xn.shopvault.catalog.CatalogDtos.ProductRequest;
import com.r4xn.shopvault.common.PageResponse;
import com.r4xn.shopvault.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCatalogController {

    private final CatalogService catalog;

    public AdminCatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/products")
    public PageResponse<AdminProductResponse> products(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) UUID category,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return catalog.adminSearch(q, category, page, size);
    }

    @GetMapping("/products/{id}")
    public AdminProductResponse product(@PathVariable UUID id) {
        return catalog.adminGet(id);
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminProductResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProductRequest request) {
        return catalog.create(CurrentUser.id(jwt), request);
    }

    @PutMapping("/products/{id}")
    public AdminProductResponse update(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ProductRequest request) {
        return catalog.update(CurrentUser.id(jwt), id, request);
    }

    @DeleteMapping("/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        catalog.deactivate(CurrentUser.id(jwt), id);
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse createCategory(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CategoryRequest request) {
        return catalog.createCategory(CurrentUser.id(jwt), request);
    }

    @PutMapping("/categories/{id}")
    public CategoryResponse updateCategory(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        return catalog.updateCategory(CurrentUser.id(jwt), id, request);
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        catalog.deleteCategory(CurrentUser.id(jwt), id);
    }
}
