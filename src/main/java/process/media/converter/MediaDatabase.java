package process.media.converter;

import com.zaxxer.hikari.HikariDataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;

import javax.sql.DataSource;

/**
 * Connects to media_db and brings its schema up to date (MIG-41), as NotificationsDatabase did for
 * notifications_db while Notifications still lived here.
 *
 * Deliberately not a DataSource, EntityManagerFactory, TransactionManager or SpringLiquibase bean:
 * Spring Boot configures etl_job's JPA, pool, transactions and Liquibase only while there is no bean
 * of those types. The pool and its migration live inside the one store bean; the pool closes with
 * the application context (close() below).
 *
 * @author Nabeel Ahmed
 */
@Configuration
public class MediaDatabase {

    static final String CHANGELOG = "classpath:db/media/changelog-master.yaml";

    private HikariDataSource pool;

    @Bean
    public ConverterTaskStore converterTaskStore(@Value("${media.datasource.url}") String url,
        @Value("${media.datasource.username}") String username, @Value("${media.datasource.password}") String password) {
        this.pool = pool(url, username, password);
        try {
            migrate(this.pool);
        } catch (RuntimeException failed) {
            this.pool.close();
            throw failed;
        }
        return new ConverterTaskStore(this.pool);
    }

    @javax.annotation.PreDestroy
    public void close() {
        if (this.pool != null) {
            this.pool.close();
        }
    }

    static HikariDataSource pool(String url, String username, String password) {
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName("media");
        pool.setJdbcUrl(url);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(3);
        return pool;
    }

    static void migrate(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(CHANGELOG);
        liquibase.setResourceLoader(new DefaultResourceLoader(MediaDatabase.class.getClassLoader()));
        try {
            liquibase.afterPropertiesSet();
        } catch (Exception failed) {
            throw new IllegalStateException("Could not bring media_db up to date: " + failed.getMessage(), failed);
        }
    }
}
