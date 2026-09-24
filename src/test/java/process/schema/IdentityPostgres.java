package process.schema;

import com.zaxxer.hikari.HikariDataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.hibernate.dialect.PostgreSQLDialect;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A throwaway etl_job built by the real changelog, with process's entities and repositories on top.
 *
 * Opt-in, like EtlJobChangelogPostgresTest: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD name a
 * Postgres server; a database is created for the test class and dropped after it. What this adds is
 * JPA: the Identity tests need the Hibernate tenant filter and the repositories' own queries, neither
 * of which a JdbcTemplate can show.
 */
public final class IdentityPostgres implements AutoCloseable {

    private final String serverUrl;
    private final String user;
    private final String password;
    private final String scratch;
    private final HikariDataSource pool;
    private LocalContainerEntityManagerFactoryBean factoryBean;
    private EntityManager entityManager;
    private TransactionTemplate transaction;

    private IdentityPostgres(String serverUrl, String user, String password, String scratch) throws Exception {
        this.serverUrl = serverUrl;
        this.user = user;
        this.password = password;
        this.scratch = scratch;
        try (Connection admin = DriverManager.getConnection(serverUrl, user, password); Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + scratch);
        }
        this.pool = new HikariDataSource();
        this.pool.setJdbcUrl(serverUrl.replaceAll("/[^/?]+(\\?.*)?$", "/" + scratch + "$1"));
        this.pool.setUsername(user);
        this.pool.setPassword(password);
        this.pool.setMaximumPoolSize(4);
    }

    /** Skips the calling test when no server is configured. */
    public static IdentityPostgres create(String purpose) throws Exception {
        String server = System.getenv("NOTIFICATIONS_TEST_DB_URL");
        assumeTrue(server != null, "NOTIFICATIONS_TEST_DB_URL is not set");
        return new IdentityPostgres(server, System.getenv("NOTIFICATIONS_TEST_DB_USER"),
            System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"),
            purpose + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
    }

    /** Runs db.changelog-master.yaml against the scratch database, as a new environment would. */
    public IdentityPostgres migrate() throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(this.pool);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.setContexts("init");
        liquibase.setResourceLoader(new DefaultResourceLoader(getClass().getClassLoader()));
        liquibase.afterPropertiesSet();
        return this;
    }

    public JdbcTemplate sql() {
        return new JdbcTemplate(this.pool);
    }

    /** Hibernate over every process entity, validating nothing and creating nothing: Liquibase owns the schema. */
    public IdentityPostgres withJpa() {
        this.factoryBean = new LocalContainerEntityManagerFactoryBean();
        this.factoryBean.setDataSource(this.pool);
        this.factoryBean.setPackagesToScan("process.model.pojo");
        this.factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.dialect", PostgreSQLDialect.class.getName());
        properties.put("hibernate.hbm2ddl.auto", "none");
        properties.put("hibernate.physical_naming_strategy", SpringPhysicalNamingStrategy.class.getName());
        properties.put("hibernate.implicit_naming_strategy", SpringImplicitNamingStrategy.class.getName());
        this.factoryBean.setJpaPropertyMap(properties);
        this.factoryBean.afterPropertiesSet();
        EntityManagerFactory factory = this.factoryBean.getObject();
        this.entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory);
        this.transaction = new TransactionTemplate(new JpaTransactionManager(factory));
        return this;
    }

    /** The transaction-bound EntityManager, as a service's @PersistenceContext field would hold it. */
    public EntityManager entityManager() {
        return this.entityManager;
    }

    public TransactionTemplate transaction() {
        return this.transaction;
    }

    public <R> R repository(Class<R> type) {
        return new JpaRepositoryFactory(this.entityManager).getRepository(type);
    }

    @Override
    public void close() throws Exception {
        if (this.factoryBean != null) {
            this.factoryBean.destroy();
        }
        this.pool.close();
        try (Connection admin = DriverManager.getConnection(this.serverUrl, this.user, this.password);
             Statement sql = admin.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS " + this.scratch);
        }
    }
}
