package process.config;

import com.fasterxml.classmate.TypeResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;
import springfox.documentation.spring.web.plugins.WebMvcRequestHandlerProvider;
import springfox.documentation.spring.web.readers.operation.HandlerMethodResolver;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The API documentation is generated only where a setting asks for it (owner, 2026-09-24): off unless
 * process.api-docs.enabled is true, which only the dev profile says. And the service-to-service
 * endpoints under /internal -- which the gateway never lets in from outside -- are not in it at all.
 *
 * @author Nabeel Ahmed
 */
class ApiDocsSettingTest {

    @Test
    void theDocsAreOffUnlessTheSettingSaysTrue() {
        ConditionalOnProperty condition = SwaggerConfig.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).as("SwaggerConfig must be conditional on a setting").isNotNull();
        assertThat(condition.name()).containsExactly("process.api-docs.enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).as("a missing setting means off").isFalse();
    }

    @Test
    void internalEndpointsAreNotDocumented() {
        assertThat(SwaggerConfig.isDocumented("/internal/tenants/7")).isFalse();
        assertThat(SwaggerConfig.isDocumented("/internal")).isFalse();
        assertThat(SwaggerConfig.isDocumented("/internal/jwks")).isFalse();
        assertThat(SwaggerConfig.isDocumented("/error")).isFalse();
    }

    @Test
    void thePublicApiIsDocumented() {
        assertThat(SwaggerConfig.isDocumented("/sourceJob.json/fetchAllSourceJob")).isTrue();
        assertThat(SwaggerConfig.isDocumented("/changeState/{id}")).isTrue();
        assertThat(SwaggerConfig.isDocumented("/internalNotes.json/list")).as("only the /internal segment itself").isTrue();
    }

    /**
     * Boot 2.6+ (MIG-204): the actuator's mapping parses with PathPatternParser, which springfox 2.9.2 cannot
     * read -- start-up failed in documentationPluginsBootstrapper. Only the Ant-style mappings reach it.
     */
    @Test
    void springfoxIsHandedOnlyTheAntStyleMappings() {
        RequestMappingHandlerMapping controllers = new RequestMappingHandlerMapping();
        RequestMappingHandlerMapping actuatorLike = new RequestMappingHandlerMapping();
        actuatorLike.setPatternParser(new PathPatternParser());
        List<RequestMappingInfoHandlerMapping> mappings = new ArrayList<>();
        mappings.add(controllers);
        mappings.add(actuatorLike);
        WebMvcRequestHandlerProvider provider =
            new WebMvcRequestHandlerProvider(new HandlerMethodResolver(new TypeResolver()), mappings);

        Object after = SwaggerConfig.springfoxAntPathMappingsOnly().postProcessAfterInitialization(provider, "provider");

        assertThat(after).isSameAs(provider);
        assertThat(SwaggerConfig.handlerMappings(provider)).containsExactly(controllers);
    }

    @Test
    void otherBeansPassThroughUntouched() {
        Object bean = new Object();
        assertThat(SwaggerConfig.springfoxAntPathMappingsOnly().postProcessAfterInitialization(bean, "bean")).isSameAs(bean);
    }
}
