package process.tenancy;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-258: every deployed profile works as process_app (row security never applies to the superuser login), with
 * PROCESS_DB_SESSION_SQL as the way back, and Liquibase keeps the login on a connection of its own -- without that it
 * would run the changelog as process_app, which owns nothing and cannot.
 */
class RowSecurityPropertiesTest {

    @Test
    void everyDeployedProfileSetsRoleAndKeepsLiquibaseOnTheLogin() throws IOException {
        for (String profile : new String[]{"dev", "stage", "prod"}) {
            Properties p = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-" + profile + ".properties"));
            assertThat(p.getProperty("spring.datasource.hikari.connection-init-sql")).as(profile)
                .isEqualTo("${PROCESS_DB_SESSION_SQL:SET ROLE process_app}");
            assertThat(p.getProperty("spring.liquibase.user")).as(profile).isEqualTo("${spring.datasource.username}");
            assertThat(p.getProperty("spring.liquibase.password")).as(profile).isEqualTo("${spring.datasource.password}");
        }
    }
}
