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
 * The avatar bucket property has four readers and they must all name the same bucket.
 *
 * StorageBrowserServiceImpl guards it, StorageConnectionServiceImpl reserves its alias,
 * AppUserServiceImpl writes pictures to it and StorageConnectionBootstrap creates the connection.
 * Three took the raw property while the bootstrap trimmed it and substituted a default for a blank
 * one, so AVATAR_BUCKET="" or a value with a trailing space produced a connection called
 * "etl-avatar" that nothing else asked for -- every upload failed with "Unknown bucket" while the
 * storage screen showed a connection that looked right.
 *
 * They now share one expression. This asserts what that expression does, rather than that four
 * annotations are spelt the same, so it still holds if a fifth reader is added.
 *
 * A real container, because the value is produced by SpEL over a resolved placeholder and only a
 * context does both halves -- which also means a typo in the expression fails here rather than at
 * application startup.
 *
 * @author Nabeel Ahmed
 */
class AvatarBucketPropertyTest {

    @Configuration
    static class Holder {
        @Value(StoragePropertyDefaults.AVATAR_BUCKET)
        String avatarBucket;
    }

    private String resolvedWith(String configured) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            if (configured != null) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test", Collections.singletonMap("app.avatar.bucket", configured)));
            }
            context.register(PropertySourcesPlaceholderConfigurer.class, Holder.class);
            context.refresh();
            return context.getBean(Holder.class).avatarBucket;
        }
    }

    @Test
    void anUnsetPropertyIsTheDocumentedDefault() {
        assertThat(this.resolvedWith(null)).isEqualTo("etl-avatar");
    }

    @Test
    void aConfiguredBucketIsUsedAsGiven() {
        assertThat(this.resolvedWith("company-avatars")).isEqualTo("company-avatars");
    }

    @Test
    void surroundingWhitespaceIsRemovedRatherThanBecomingPartOfTheName() {
        // The trap this closes: the bootstrap created "etl-avatar" while the uploader wrote to
        // " etl-avatar", so the two never met.
        assertThat(this.resolvedWith("  etl-avatar  ")).isEqualTo("etl-avatar");
        assertThat(this.resolvedWith("company-avatars\t")).isEqualTo("company-avatars");
    }

    @Test
    void aBlankValueFallsBackRatherThanNamingNoBucketAtAll() {
        assertThat(this.resolvedWith("")).isEqualTo("etl-avatar");
        assertThat(this.resolvedWith("   ")).isEqualTo("etl-avatar");
    }
}
