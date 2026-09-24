package process.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-67 (DEF-064): no profile turns hibernate.enable_lazy_load_no_trans back on. Every lazy read it hid is
 * now an explicit fetch (LazyLoadOutsideTransactionPostgresTest); re-enabling it would hide the next one.
 */
class LazyLoadFlagTest {

    @Test
    void noProfileLoadsLazilyOutsideATransaction() throws Exception {
        for (String profile : new String[] {"application.properties", "application-dev.properties", "application-stage.properties",
            "application-prod.properties"}) {
            Properties properties = new Properties();
            try (InputStream stream = this.getClass().getClassLoader().getResourceAsStream(profile)) {
                assertThat(stream).as(profile).isNotNull();
                properties.load(stream);
            }
            assertThat(properties.getProperty("spring.jpa.properties.hibernate.enable_lazy_load_no_trans", "false"))
                .as(profile).isEqualTo("false");
        }
    }
}
