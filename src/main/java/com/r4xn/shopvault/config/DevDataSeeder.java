package com.r4xn.shopvault.config;

import com.r4xn.shopvault.catalog.Category;
import com.r4xn.shopvault.catalog.CategoryRepository;
import com.r4xn.shopvault.catalog.Product;
import com.r4xn.shopvault.catalog.ProductRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Demo catalogue for local development only. */
@Component
@Profile("dev")
@Order(1)
class DevDataSeeder implements ApplicationRunner {

    private final CategoryRepository categories;
    private final ProductRepository products;

    DevDataSeeder(CategoryRepository categories, ProductRepository products) {
        this.categories = categories;
        this.products = products;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (products.count() > 0) {
            return;
        }
        Category coffee = categories.save(new Category("Coffee", "coffee"));
        Category gear = categories.save(new Category("Brewing Gear", "brewing-gear"));
        products.save(new Product("COF-HOUSE-250", "House Blend 250g", "Medium roast, chocolate and caramel notes.",
                3_500, 120, coffee));
        products.save(new Product("COF-SINGLE-250", "Single Origin Sabah 250g", "Light roast from Ranau, Sabah.",
                5_200, 40, coffee));
        products.save(new Product("GEAR-V60", "Pour-over Dripper", "Ceramic dripper, size 02.", 8_900, 25, gear));
        products.save(new Product("GEAR-SCALE", "Brew Scale", "0.1 g resolution with timer.", 12_900, 10, gear));
        products.save(new Product("GEAR-KETTLE", "Gooseneck Kettle", "1 litre, stainless steel.", 15_900, 0, gear));
    }
}
