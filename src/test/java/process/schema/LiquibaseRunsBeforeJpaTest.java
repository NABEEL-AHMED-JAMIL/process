package process.schema;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The changelog runs before Hibernate validates the entities against the schema (MIG-204).
 *
 * spring.jpa.defer-datasource-initialization=true makes every database initializer Spring Boot knows of, Liquibase
 * included, depend on the EntityManagerFactory (Boot 2.5+, JpaDatabaseInitializerDetector). With ddl-auto=validate
 * that is validation first and migration second: a release whose entity maps a column its own changeset adds
 * fails at startup, and an empty database never gets built. Boot 2.3 ignored the property, which is how every
 * profile came to carry it for years without harm.
 */
class LiquibaseRunsBeforeJpaTest {

    private static final List<String> FILES = Arrays.asList("application.properties",
        "application-dev.properties", "application-stage.properties", "application-prod.properties");

    @Test
    void noProfileDefersDatabaseInitializationBehindJpa() throws Exception {
        for (String file : FILES) {
            Properties properties = new Properties();
            try (InputStream in = LiquibaseRunsBeforeJpaTest.class.getClassLoader().getResourceAsStream(file)) {
                assertThat(in).as(file).isNotNull();
                properties.load(in);
            }
            assertThat(properties.getProperty("spring.jpa.defer-datasource-initialization", "false"))
                .as(file + ": spring.jpa.defer-datasource-initialization").isEqualToIgnoringCase("false");
            assertThat(properties.getProperty("spring.liquibase.enabled", "true"))
                .as(file + ": spring.liquibase.enabled").isEqualToIgnoringCase("true");
        }
    }
}
