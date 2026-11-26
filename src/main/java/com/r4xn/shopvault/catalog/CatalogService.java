package com.r4xn.shopvault.catalog;

import com.r4xn.shopvault.audit.AuditService;
import com.r4xn.shopvault.catalog.CatalogDtos.AdminProductResponse;
import com.r4xn.shopvault.catalog.CatalogDtos.CategoryRequest;
import com.r4xn.shopvault.catalog.CatalogDtos.CategoryResponse;
import com.r4xn.shopvault.catalog.CatalogDtos.ProductRequest;
import com.r4xn.shopvault.catalog.CatalogDtos.ProductResponse;
import com.r4xn.shopvault.catalog.CatalogDtos.ProductSort;
import com.r4xn.shopvault.common.ApiException;
import com.r4xn.shopvault.common.PageResponse;
import com.r4xn.shopvault.config.AppProperties;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final AuditService audit;
    private final String currency;

    public CatalogService(
            ProductRepository products, CategoryRepository categories, AuditService audit, AppProperties props) {
        this.products = products;
        this.categories = categories;
        this.audit = audit;
        this.currency = props.shop().currency();
    }

    // ---- public catalogue ----

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> search(String query, UUID categoryId, ProductSort sort, int page, int size) {
        Specification<Product> spec = filter(query, categoryId, true);
        var result = products.findAll(spec, PageRequest.of(page, size, sortFor(sort)));
        return PageResponse.of(result, this::toPublic);
    }

    @Transactional(readOnly = true)
    public ProductResponse getActive(UUID id) {
        return products.findById(id)
                .filter(Product::isActive)
                .map(this::toPublic)
                .orElseThrow(() -> ApiException.notFound("Product"));
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> categories() {
        return categories.findAllByOrderByNameAsc().stream().map(CategoryResponse::from).toList();
    }

    // ---- admin ----

    @Transactional(readOnly = true)
    public PageResponse<AdminProductResponse> adminSearch(String query, UUID categoryId, int page, int size) {
        var result = products.findAll(filter(query, categoryId, false),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(result, this::toAdmin);
    }

    @Transactional(readOnly = true)
    public AdminProductResponse adminGet(UUID id) {
        return products.findById(id).map(this::toAdmin).orElseThrow(() -> ApiException.notFound("Product"));
    }

    @Transactional
    public AdminProductResponse create(UUID actor, ProductRequest request) {
        if (products.existsBySku(request.sku())) {
            throw ApiException.conflict("sku_taken", "A product with this SKU already exists");
        }
        Product product = new Product(request.sku(), request.name().strip(), clean(request.description()),
                request.priceCents(), request.stock(), category(request.categoryId()));
        if (Boolean.FALSE.equals(request.active())) {
            product.deactivate();
        }
        products.save(product);
        audit.record(actor, "PRODUCT_CREATED", "product:" + product.getId(), "sku=" + product.getSku());
        return toAdmin(product);
    }

    @Transactional
    public AdminProductResponse update(UUID actor, UUID id, ProductRequest request) {
        Product product = products.findById(id).orElseThrow(() -> ApiException.notFound("Product"));
        if (products.existsBySkuAndIdNot(request.sku(), id)) {
            throw ApiException.conflict("sku_taken", "A product with this SKU already exists");
        }
        long oldPrice = product.getPriceCents();
        boolean active = request.active() == null ? product.isActive() : request.active();
        product.update(request.sku(), request.name().strip(), clean(request.description()), request.priceCents(),
                request.stock(), category(request.categoryId()), active);
        audit.record(actor, "PRODUCT_UPDATED", "product:" + id,
                oldPrice != request.priceCents() ? "price " + oldPrice + " -> " + request.priceCents() : null);
        return toAdmin(product);
    }

    /** Products are never hard-deleted: past orders reference them. */
    @Transactional
    public void deactivate(UUID actor, UUID id) {
        Product product = products.findById(id).orElseThrow(() -> ApiException.notFound("Product"));
        product.deactivate();
        audit.record(actor, "PRODUCT_DEACTIVATED", "product:" + id, null);
    }

    @Transactional
    public CategoryResponse createCategory(UUID actor, CategoryRequest request) {
        if (categories.existsBySlug(request.slug())) {
            throw ApiException.conflict("slug_taken", "A category with this slug already exists");
        }
        Category category = categories.save(new Category(request.name().strip(), request.slug()));
        audit.record(actor, "CATEGORY_CREATED", "category:" + category.getId(), category.getSlug());
        return CategoryResponse.from(category);
    }

    @Transactional
    public CategoryResponse updateCategory(UUID actor, UUID id, CategoryRequest request) {
        Category category = categories.findById(id).orElseThrow(() -> ApiException.notFound("Category"));
        if (categories.existsBySlugAndIdNot(request.slug(), id)) {
            throw ApiException.conflict("slug_taken", "A category with this slug already exists");
        }
        category.rename(request.name().strip(), request.slug());
        audit.record(actor, "CATEGORY_UPDATED", "category:" + id, category.getSlug());
        return CategoryResponse.from(category);
    }

    @Transactional
    public void deleteCategory(UUID actor, UUID id) {
        Category category = categories.findById(id).orElseThrow(() -> ApiException.notFound("Category"));
        if (products.existsByCategoryId(id)) {
            throw ApiException.conflict("category_in_use", "Move or deactivate the products in this category first");
        }
        categories.delete(category);
        audit.record(actor, "CATEGORY_DELETED", "category:" + id, category.getSlug());
    }

    // ---- helpers ----

    private Category category(UUID id) {
        if (id == null) {
            return null;
        }
        return categories.findById(id)
                .orElseThrow(() -> ApiException.badRequest("unknown_category", "The category does not exist"));
    }

    private static Specification<Product> filter(String query, UUID categoryId, boolean activeOnly) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (activeOnly) {
                predicates.add(cb.isTrue(root.get("active")));
            }
            if (query != null && !query.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("name")), likePattern(query), '\\'));
            }
            if (categoryId != null) {
                predicates.add(cb.equal(root.get("category").get("id"), categoryId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** User text is a literal, never a pattern: %, _ and \ are escaped. */
    static String likePattern(String query) {
        String escaped = query.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static Sort sortFor(ProductSort sort) {
        return switch (sort == null ? ProductSort.NEWEST : sort) {
            case NEWEST -> Sort.by(Sort.Direction.DESC, "createdAt");
            case PRICE_ASC -> Sort.by(Sort.Direction.ASC, "priceCents").and(Sort.by("id"));
            case PRICE_DESC -> Sort.by(Sort.Direction.DESC, "priceCents").and(Sort.by("id"));
            case NAME -> Sort.by(Sort.Direction.ASC, "name").and(Sort.by("id"));
        };
    }

    private static String clean(String description) {
        if (description == null) {
            return null;
        }
        String stripped = description.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private ProductResponse toPublic(Product p) {
        return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPriceCents(),
                currency, p.getStock() > 0, p.getCategory() == null ? null : CategoryResponse.from(p.getCategory()));
    }

    private AdminProductResponse toAdmin(Product p) {
        return new AdminProductResponse(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPriceCents(),
                currency, p.getStock(), p.isActive(),
                p.getCategory() == null ? null : CategoryResponse.from(p.getCategory()), p.getCreatedAt());
    }
}
