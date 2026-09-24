package process;

import org.hibernate.dialect.PostgreSQL10Dialect;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * The application's own JPA over a ScratchPostgres database: its entities, Spring Boot's naming
 * strategies, and Spring Data repositories built from the real interfaces -- so a native query is run
 * the way the application runs it (Hibernate binding a list parameter, mapping the row to an entity,
 * Optional for a single result), without booting the application or touching any existing database.
 *
 * @author Nabeel Ahmed
 */
public final class ScratchJpa implements AutoCloseable {

    private final LocalContainerEntityManagerFactoryBean factoryBean;
    private final EntityManagerFactory factory;
    private final JpaTransactionManager transactionManager;
    private final JpaRepositoryFactory repositories;

    public ScratchJpa(ScratchPostgres db) {
        this.factoryBean = new LocalContainerEntityManagerFactoryBean();
        this.factoryBean.setDataSource(db.pool());
        this.factoryBean.setPackagesToScan("process.model.pojo");
        this.factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.dialect", PostgreSQL10Dialect.class.getName());
        properties.put("hibernate.hbm2ddl.auto", "none");
        properties.put("hibernate.physical_naming_strategy", SpringPhysicalNamingStrategy.class.getName());
        properties.put("hibernate.implicit_naming_strategy", SpringImplicitNamingStrategy.class.getName());
        this.factoryBean.setJpaPropertyMap(properties);
        this.factoryBean.afterPropertiesSet();
        this.factory = this.factoryBean.getObject();
        this.transactionManager = new JpaTransactionManager(this.factory);
        this.transactionManager.setDataSource(db.pool());
        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(this.factory);
        this.repositories = new JpaRepositoryFactory(shared);
    }

    /** An entity manager bound to the current transaction, as @PersistenceContext injects one. */
    public EntityManager sharedEntityManager() {
        return SharedEntityManagerCreator.createSharedEntityManager(this.factory);
    }

    public <T> T repository(Class<T> type) {
        return this.repositories.getRepository(type);
    }

    public JpaTransactionManager transactionManager() {
        return this.transactionManager;
    }

    public TransactionTemplate transactions() {
        return new TransactionTemplate(this.transactionManager);
    }

    @Override
    public void close() {
        this.factoryBean.destroy();
    }
}
