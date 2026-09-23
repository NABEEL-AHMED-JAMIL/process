package process.notifications.store;

import com.zaxxer.hikari.HikariDataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;

import javax.sql.DataSource;

/**
 * Connects to notifications_db and brings its schema up to date (MIG-21).
 *
 * Deliberately not a DataSource, EntityManagerFactory, TransactionManager or SpringLiquibase bean:
 * Spring Boot configures etl_job's JPA, pool, transactions and Liquibase only while there is no
 * bean of those types, and a second one of any of them would quietly switch the first off. The pool
 * and its migration live inside the one bean this exposes, and close with it.
 *
 * @author Nabeel Ahmed
 */
@Configuration
public class NotificationsDatabase {

    static final String CHANGELOG = "classpath:db/notifications/changelog-master.yaml";

    @Bean
    public NotificationStore notificationStore(@Value("${notifications.datasource.url}") String url,
        @Value("${notifications.datasource.username}") String username,
        @Value("${notifications.datasource.password}") String password) {
        HikariDataSource pool = pool(url, username, password);
        try {
            migrate(pool);
        } catch (RuntimeException failed) {
            pool.close();
            throw failed;
        }
        return new NotificationStore(pool);
    }

    static HikariDataSource pool(String url, String username, String password) {
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName("notifications");
        pool.setJdbcUrl(url);
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(5);
        return pool;
    }

    static void migrate(DataSource dataSource) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(CHANGELOG);
        liquibase.setResourceLoader(new DefaultResourceLoader(NotificationsDatabase.class.getClassLoader()));
        try {
            liquibase.afterPropertiesSet();
        } catch (Exception failed) {
            throw new IllegalStateException("Could not bring notifications_db up to date: " + failed.getMessage(), failed);
        }
    }
}
