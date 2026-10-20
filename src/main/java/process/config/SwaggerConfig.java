package process.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The API documentation (/v3/api-docs, /swagger-ui.html). Generated only where process.api-docs.enabled
 * is true -- the dev profile; stage and prod say false, and springdoc's own switches follow it
 * (application.properties) -- and readable only by a platform admin (SecurityConfig). The service-to-service
 * endpoints under /internal are left out: the gateway never lets them in from outside, and a published map of them
 * helps no caller who may use them.
 *
 * MIG-389: springdoc 3 (OpenAPI 3) in place of springfox 2.9.2 (Swagger 2), which does not run on Spring 6+.
 *
 * @author Nabeel Ahmed
 * */
@Configuration
@ConditionalOnProperty(name = "process.api-docs.enabled", havingValue = "true")
public class SwaggerConfig {

    @Bean
    public OpenAPI processOpenApi() {
        return new OpenAPI().info(new Info().title("Process API").description("Basic ETL Api.").version("1.0")
            .termsOfService("Terms of service")
            .contact(new Contact().name("Nabeel Ahmed Jamil").url("www.process.com").email("nabeel.amd93@gmail.com"))
            .license(new License().name("License of API").url("API license URL")));
    }

    /** The documented endpoints: see isDocumented. */
    @Bean
    public GroupedOpenApi documentedEndpoints() {
        return GroupedOpenApi.builder().group("process").pathsToMatch("/**")
            .addOpenApiCustomizer(api -> {
                if (api.getPaths() != null) {
                    api.getPaths().keySet().removeIf(path -> !isDocumented(path));
                }
            }).build();
    }

    /** Every endpoint but the /internal ones and Spring's /error. */
    static boolean isDocumented(String path) {
        if (path == null || path.equals("/error")) {
            return false;
        }
        return !(path.equals("/internal") || path.startsWith("/internal/"));
    }
}
