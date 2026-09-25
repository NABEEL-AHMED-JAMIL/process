package process.schema;

import com.zaxxer.hikari.HikariDataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.hibernate.dialect.PostgreSQL10Dialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-129's schema gate for etl_job, run by etl-platform/scripts/schema-gate.sh against an empty database it made
 * (see SchemaGate). The changelog builds it from nothing as process runs it at start-up (context init, the dev
 * profile's parameters); Hibernate then validates every entity against what it built, with the ddl-auto every
 * profile sets, which must be validate; and the changelog, run once, has nothing left to run.
 *
 * The JPA layer is built as EntitiesValidateAgainstChangelogPostgresTest builds it -- the application class itself
 * needs the whole context. Skipped unless SCHEMA_GATE_DB_URL is set: the gate is the script.
 */
@EnabledIfEnvironmentVariable(named = SchemaGate.URL_VARIABLE, matches = ".+")
class SchemaGatePostgresTest {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";

    private static SchemaGate gate;
    private static HikariDataSource pool;

    @BeforeAll
    static void theChangelogBuildsTheGateDatabase() throws Exception {
        gate = SchemaGate.fromEnvironment();
        pool = new HikariDataSource();
        pool.setJdbcUrl(gate.url);
        pool.setUsername(gate.user);
        pool.setPassword(gate.password);
        pool.setMaximumPoolSize(4);
        Properties dev = profile("application-dev.properties");
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(pool);
        liquibase.setChangeLog("classpath:" + CHANGELOG);
        liquibase.setContexts(dev.getProperty("spring.liquibase.contexts"));
        Map<String, String> parameters = new HashMap<>();
        parameters.put("platformKafkaBootstrapServers", "localhost:9092");
        parameters.put("platformKafkaSecurityProtocol", "PLAINTEXT");
        liquibase.setChangeLogParameters(parameters);
        liquibase.setResourceLoader(new DefaultResourceLoader(SchemaGatePostgresTest.class.getClassLoader()));
        liquibase.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        if (pool != null) {
            pool.close();
        }
    }

    private static Properties profile(String resource) throws Exception {
        Properties properties = new Properties();
        try (InputStream in = SchemaGatePostgresTest.class.getClassLoader().getResourceAsStream(resource)) {
            properties.load(in);
        }
        return properties;
    }

    @Test
    void everyEntityValidatesAgainstTheChangelogBuiltDatabase() throws Exception {
        for (String profile : new String[] {"application-dev.properties", "application-stage.properties", "application-prod.properties"}) {
            assertThat(profile(profile).getProperty("spring.jpa.hibernate.ddl-auto")).as(profile).isEqualTo("validate");
        }
        LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setDataSource(pool);
        factoryBean.setPackagesToScan("process.model.pojo");
        factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.dialect", PostgreSQL10Dialect.class.getName());
        properties.put("hibernate.hbm2ddl.auto", "validate");
        properties.put("hibernate.physical_naming_strategy", SpringPhysicalNamingStrategy.class.getName());
        properties.put("hibernate.implicit_naming_strategy", SpringImplicitNamingStrategy.class.getName());
        factoryBean.setJpaPropertyMap(properties);
        try {
            assertThatCode(factoryBean::afterPropertiesSet).doesNotThrowAnyException();
        } finally {
            factoryBean.destroy();
        }
    }

    /**
     * Changesets that may be left unrun on a database built from nothing, each with the reason. Adding one is a
     * decision, made in review: a changeset in this list is one a new environment never finishes.
     */
    private static final Map<String, String> WAITING_BY_DESIGN = new HashMap<>();

    static {
        // It waits for identity-service/scripts/copy-from-etl-job.sh to freeze the six Identity tables, and a new
        // environment has nothing to copy, so it waits there forever: the V85.0 triggers it drops stay, and they
        // overwrite a job's assigned_username from an app_user nobody writes. Freezing on a fresh build instead
        // breaks the Postgres tests that seed tenant/app_user rows. An owner decision (MIG-129 report).
        WAITING_BY_DESIGN.put("69.1-identity-cutover", "waits for the identity copy; a new database has nothing to copy");
    }

    @Test
    void everyMigrationHasRunOnceAndNoneIsLeft() throws Exception {
        assertThat(gate.ran()).isPositive();
        assertThat(gate.unrun(CHANGELOG, profile("application-dev.properties").getProperty("spring.liquibase.contexts")))
            .as("changesets a new etl_job never runs").isSubsetOf(WAITING_BY_DESIGN.keySet());
    }

    /** Once built, the database is no longer one the gate may run a changelog against. */
    @Test
    void theGateRefusesADatabaseThatAlreadyHoldsTables() {
        assertThatThrownBy(() -> SchemaGate.open(gate.url, gate.user, gate.password))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("already holds tables");
    }
}
