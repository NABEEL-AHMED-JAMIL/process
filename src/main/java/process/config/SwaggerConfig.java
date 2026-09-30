package process.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.spring.web.plugins.WebMvcRequestHandlerProvider;
import springfox.documentation.swagger2.annotations.EnableSwagger2;
import java.lang.reflect.Field;
import java.util.List;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Contact;
import java.util.Collections;

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

    /**
     * Keeps springfox 2.9.2 to the handler mappings it can read (MIG-204). Since Spring Boot 2.6 the actuator's
     * endpoint mapping always parses its paths with PathPatternParser, whatever spring.mvc.pathmatch says, so
     * its RequestMappingInfos carry no Ant-style patterns condition and springfox's ordering of them failed
     * start-up with a NullPointerException in documentationPluginsBootstrapper. The application's own
     * controllers use AntPathMatcher (application.properties) and stay documented; the actuator endpoints,
     * which the docs listed under Boot 2.3, no longer are.
     */
    @Bean
    public static BeanPostProcessor springfoxAntPathMappingsOnly() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof WebMvcRequestHandlerProvider) {
                    antPathMappingsOnly(handlerMappings(bean));
                }
                return bean;
            }
        };
    }

    /** Drops, in place, every mapping that matches with PathPatternParser. */
    static void antPathMappingsOnly(List<RequestMappingInfoHandlerMapping> mappings) {
        mappings.removeIf(mapping -> mapping.getPatternParser() != null);
    }

    @SuppressWarnings("unchecked")
    static List<RequestMappingInfoHandlerMapping> handlerMappings(Object provider) {
        Field field = ReflectionUtils.findField(provider.getClass(), "handlerMappings");
        if (field == null) {
            throw new IllegalStateException("springfox's WebMvcRequestHandlerProvider has no handlerMappings field;"
                + " SwaggerConfig.springfoxAntPathMappingsOnly was written for springfox 2.9.2");
        }
        ReflectionUtils.makeAccessible(field);
        return (List<RequestMappingInfoHandlerMapping>) ReflectionUtils.getField(field, provider);
    }

    /** Every endpoint but the /internal ones and Spring's /error. */
    static boolean isDocumented(String path) {
        if (path == null || path.equals("/error")) {
            return false;
        }
        return !(path.equals("/internal") || path.startsWith("/internal/"));
    }

    private ApiInfo apiInfo() {
        return new ApiInfo("Process API", "Basic ETL Api.", "1.0", "Terms of service",
            new Contact("Nabeel Ahmed Jamil", "www.process.com", "nabeel.amd93@gmail.com"), "License of API", "API license URL",
                Collections.emptyList());
    }

}
