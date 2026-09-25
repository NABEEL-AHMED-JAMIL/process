package process.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;

import javax.sql.DataSource;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * MIG-67 (DEF-064): no profile turns hibernate.enable_lazy_load_no_trans back on. Every lazy read it hid is
 * now an explicit fetch (LazyLoadOutsideTransactionPostgresTest); re-enabling it would hide the next one.
 *
 * MIG-261: and no profile keeps a session open for the whole web request. spring.jpa.open-in-view is Spring
 * Boot's default, on, and it let JSON rendering -- after the service's transaction had closed -- load whatever
 * lazy association the response reached. It is declared off in every deployed profile, so a response is only
 * ever what its service read (OpenInViewOffWebPostgresTest drives the endpoints that way).
 */
class LazyLoadFlagTest {

    private static final String[] DEPLOYED = {"application-dev.properties", "application-stage.properties",
        "application-prod.properties"};

    /** What Spring Boot 2.3's JpaWebConfiguration says when the property is left to its default. */
    private static final String BOOT_WARNING = "spring.jpa.open-in-view is enabled by default";

    private static Properties load(String profile) throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = LazyLoadFlagTest.class.getClassLoader().getResourceAsStream(profile)) {
            assertThat(stream).as(profile).isNotNull();
            properties.load(stream);
        }
        return properties;
    }

    @Test
    void noProfileLoadsLazilyOutsideATransaction() throws Exception {
        for (String profile : new String[] {"application.properties", "application-dev.properties", "application-stage.properties",
            "application-prod.properties"}) {
            assertThat(load(profile).getProperty("spring.jpa.properties.hibernate.enable_lazy_load_no_trans", "false"))
                .as(profile).isEqualTo("false");
        }
    }

    /** Declared, not defaulted: an absent line is Boot's default, which is on. */
    @Test
    void everyDeployedProfileDeclaresOpenInViewOff() throws Exception {
        for (String profile : DEPLOYED) {
            assertThat(load(profile).getProperty("spring.jpa.open-in-view")).as(profile).isEqualTo("false");
        }
        // The shared file must not turn it back on underneath the profiles either.
        assertThat(load("application.properties").getProperty("spring.jpa.open-in-view", "false")).isEqualTo("false");
    }

    /**
     * What Boot does with each profile's value: no OpenEntityManagerInViewInterceptor is registered, and the
     * startup WARN about open-in-view is not written.
     */
    @Test
    void bootRegistersNoOpenInViewInterceptorAndDoesNotWarnForAnyProfile() throws Exception {
        for (String profile : DEPLOYED) {
            String value = load(profile).getProperty("spring.jpa.open-in-view");
            List<String> warnings = this.boot("spring.jpa.open-in-view=" + value, false);
            assertThat(warnings).as(profile).noneMatch(line -> line.contains(BOOT_WARNING));
        }
    }

    /** The control: left undeclared, Boot registers the interceptor and warns -- so the test above can see both. */
    @Test
    void leftUndeclaredBootOpensTheSessionPerRequestAndWarns() {
        List<String> warnings = this.boot(null, true);
        assertThat(warnings).anyMatch(line -> line.contains(BOOT_WARNING));
    }

    /** Boots Boot's JPA and MVC auto-configuration over a DataSource nobody connects to; returns the WARN lines. */
    private List<String> boot(String openInView, boolean expectInterceptor) {
        Logger logger = (Logger) LoggerFactory.getLogger("org.springframework.boot.autoconfigure.orm.jpa");
        Level before = logger.getLevel();
        boolean additive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.WARN);
        logger.setAdditive(false);
        logger.addAppender(appender);
        try {
            WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class,
                    TransactionAutoConfiguration.class, WebMvcAutoConfiguration.class))
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withPropertyValues(
                    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
                    "spring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults=false");
            if (openInView != null) {
                runner = runner.withPropertyValues(openInView);
            }
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                if (expectInterceptor) {
                    assertThat(context).hasSingleBean(OpenEntityManagerInViewInterceptor.class);
                } else {
                    assertThat(context).doesNotHaveBean(OpenEntityManagerInViewInterceptor.class);
                }
            });
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
            logger.setAdditive(additive);
        }
        return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
            .map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
    }
}
