package process.schema;

import org.hibernate.dialect.PostgreSQL10Dialect;
import org.junit.jupiter.api.Test;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import process.ScratchPostgres;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Stage and prod boot with spring.jpa.hibernate.ddl-auto=validate, which refuses to start when an entity's column
 * type disagrees with the table's. A database built from the changelog has to pass it, or a fresh environment does
 * not boot. scheduler.day_of_month did not: smallint in the V50 baseline, Integer in the entity; V131 widens it.
 *
 * Hibernate stops at the first mismatch, so this names one at a time.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchPostgres).
 */
class EntitiesValidateAgainstChangelogPostgresTest {

    @Test
    void theEntitiesValidateAgainstAChangelogBuiltEtlJob() throws Exception {
        try (ScratchPostgres db = ScratchPostgres.create("validate_entities")) {
            LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
            factoryBean.setDataSource(db.pool());
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
    }
}
