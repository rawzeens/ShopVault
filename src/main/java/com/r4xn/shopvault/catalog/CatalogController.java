package com.r4xn.shopvault.catalog;

import com.r4xn.shopvault.catalog.CatalogDtos.CategoryResponse;
import com.r4xn.shopvault.catalog.CatalogDtos.ProductResponse;
import com.r4xn.shopvault.catalog.CatalogDtos.ProductSort;
import com.r4xn.shopvault.common.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public, read-only storefront API. Inactive products are invisible here. */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/products")
    public PageResponse<ProductResponse> products(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) UUID category,
            @RequestParam(defaultValue = "NEWEST") ProductSort sort,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return catalog.search(q, category, sort, page, size);
    }

    @GetMapping("/products/{id}")
    public ProductResponse product(@PathVariable UUID id) {
        return catalog.getActive(id);
    }

    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return catalog.categories();
    }
}
