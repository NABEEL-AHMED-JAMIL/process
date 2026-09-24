package process.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.*;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;
import java.util.*;

/**
 * The API documentation (/v2/api-docs, /swagger-ui.html). Generated only where process.api-docs.enabled
 * is true -- the dev profile; stage and prod say false -- and readable only by a platform admin
 * (SecurityConfig). The service-to-service endpoints under /internal are left out: the gateway never
 * lets them in from outside, and a published map of them helps no caller who may use them.
 *
 * @author Nabeel Ahmed
 * */
@Configuration
@ConditionalOnProperty(name = "process.api-docs.enabled", havingValue = "true")
@EnableSwagger2
public class SwaggerConfig {

    public Logger logger = LogManager.getLogger(SwaggerConfig.class);

    @Bean
    public Docket api() {
        return new Docket(DocumentationType.SWAGGER_2).apiInfo(apiInfo())
            .select().apis(RequestHandlerSelectors.any())
            .paths(SwaggerConfig::isDocumented).build();
    }

    /** Every endpoint but the /internal ones and Spring's /error. */
    static boolean isDocumented(String path) {
        if (path == null || path.equals("/error")) {
            return false;
        }
        return !(path.equals("/internal") || path.startsWith("/internal/"));
    }

    private ApiInfo apiInfo() {
        return new ApiInfo("Process API", "Basic ETL Api.","1.0", "Terms of service",
            new Contact("Nabeel Ahmed Jamil", "www.process.com", "nabeel.amd93@gmail.com"), "License of API", "API license URL",
                Collections.emptyList());
    }

}