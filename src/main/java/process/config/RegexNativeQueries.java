package process.config;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.query.QueryEnhancerFactories;
import org.springframework.data.jpa.repository.query.QueryEnhancerSelector;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;

/**
 * MIG-389: native queries are read as Spring Data JPA 2.7 read them -- its regex-based enhancer -- not with JSqlParser.
 * Spring Data JPA 3+ picks JSqlParser whenever it is on the class path, and needs JSqlParser 5 for it; Core has 4.6, for
 * its own read-only SQL check (QueryValidator), so every native repository query failed with a NoSuchMethodError. JPQL
 * keeps Spring Data's own (HQL on Hibernate).
 *
 * @author Nabeel Ahmed
 */
public class RegexNativeQueries extends QueryEnhancerSelector.DefaultQueryEnhancerSelector {

    public RegexNativeQueries() {
        super(QueryEnhancerFactories.fallback(), QueryEnhancerSelector.DefaultQueryEnhancerSelector.jpql());
    }

    /** Hands every repository factory bean the selector before it builds its queries. */
    @Configuration
    public static class ForRepositories {

        @Bean
        public static BeanPostProcessor regexNativeQueriesForRepositories() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof JpaRepositoryFactoryBean) {
                        ((JpaRepositoryFactoryBean<?, ?, ?>) bean).setQueryEnhancerSelectorSource(new RegexNativeQueries());
                    }
                    return bean;
                }
            };
        }
    }
}
