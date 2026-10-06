package process.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The configuration bucket property: one expression every reader shares, so a value with surrounding
 * whitespace or a blank one cannot name a bucket the others never asked for (the trap the avatar bucket
 * fell into before it left process, MIG-108).
 *
 * A real container, because the value is produced by SpEL over a resolved placeholder and only a
 * context does both halves -- which also means a typo in the expression fails here rather than at
 * application startup.
 *
 * @author Nabeel Ahmed
 */
public class ConfigBucketPropertyTest {

    @Configuration
    static class Holder {
        @Value(StoragePropertyDefaults.CONFIG_BUCKET)
        String configBucket;
    }

    private String resolvedWith(String configured) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            if (configured != null) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test", Collections.singletonMap("app.config.bucket", configured)));
            }
            context.register(PropertySourcesPlaceholderConfigurer.class, Holder.class);
            context.refresh();
            return context.getBean(Holder.class).configBucket;
        }
    }

    @Test
    void anUnsetPropertyIsTheDocumentedDefault() {
        assertThat(this.resolvedWith(null)).isEqualTo("etl-config");
    }

    @Test
    void aConfiguredBucketIsUsedAsGiven() {
        assertThat(this.resolvedWith("company-config")).isEqualTo("company-config");
    }

    @Test
    void surroundingWhitespaceIsRemovedRatherThanBecomingPartOfTheName() {
        // The trap this closes: the bootstrap created "etl-config" while a writer used " etl-config",
        // so the two never met.
        assertThat(this.resolvedWith("  etl-config  ")).isEqualTo("etl-config");
        assertThat(this.resolvedWith("company-config\t")).isEqualTo("company-config");
    }

    @Test
    void aBlankValueFallsBackRatherThanNamingNoBucketAtAll() {
        assertThat(this.resolvedWith("")).isEqualTo("etl-config");
        assertThat(this.resolvedWith("   ")).isEqualTo("etl-config");
    }
}
