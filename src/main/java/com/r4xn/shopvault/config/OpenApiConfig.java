package com.r4xn.shopvault.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI description; served only when springdoc is enabled (the dev profile). */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI shopVaultOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ShopVault API")
                        .version("v1")
                        .description("Secure e-commerce REST API. Authenticate with POST /api/v1/auth/login, "
                                + "then send 'Authorization: Bearer <accessToken>'."))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
