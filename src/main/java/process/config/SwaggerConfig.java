package process.config;

import com.google.common.base.Predicates;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.*;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;
import java.util.*;

/**
 * @author Nabeel Ahmed
 */
@Configuration
@EnableSwagger2
public class SwaggerConfig {

    public Logger logger = LogManager.getLogger(SwaggerConfig.class);

    @Bean
    public Docket api() {
        return new Docket(DocumentationType.SWAGGER_2).apiInfo(apiInfo())
            .select().apis(RequestHandlerSelectors.any())
            .paths(Predicates.not(PathSelectors.regex("/error")))
            .paths(PathSelectors.any()).build();
    }

    private ApiInfo apiInfo() {
        return new ApiInfo("Process API", "Basic IMR Api.","1.0", "Terms of service",
            new Contact("Nabeel Ahmed", "www.process.com", "nabeel.amd93@gmail.com"),
            "License of API", "API license URL", Collections.emptyList());
    }

}