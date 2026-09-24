package process.schema;

import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import process.model.dto.LookupDataDto;
import process.model.pojo.LookupData;
import process.model.pojo.SourceTask;
import process.model.repository.LookupDataRepository;
import process.model.repository.SourceTaskRepository;
import process.model.service.impl.LookupDataCacheService;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-67 (DEF-064): with hibernate.enable_lazy_load_no_trans off, as every profile now runs.
 *
 * The flag let a lazy association load after its session had closed, so a read that happened outside any
 * transaction silently worked -- and each of those reads is a future remote call once the entity is
 * another service's. With it off they fail, so each is replaced by a read that fetches what it uses.
 * Repositories here are built as the application builds them outside a transaction: a shared entity
 * manager that closes after each query, so what comes back is detached -- the exact condition of a
 * @PostConstruct, a scheduler thread, or a web request once open-in-view is off.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class LazyLoadOutsideTransactionPostgresTest {

    private static ScratchEtlJob db;
    private static LocalContainerEntityManagerFactoryBean factoryBean;
    private static LookupDataRepository lookups;
    private static SourceTaskRepository tasks;
    private static Long homePages;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("lazy_load");
        JdbcTemplate sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (2905, 'Active', 'MCN', 'MedAxis')");
        // PIPELINE_HOME_PAGES and QUEUE_FETCH_LIMIT are the ones V70.5 seeds; a workspace's home page goes under the family.
        homePages = sql.queryForObject("SELECT lookup_id FROM lookup_data WHERE lookup_type = 'PIPELINE_HOME_PAGES'", Long.class);
        sql.update("INSERT INTO lookup_data (lookup_id, lookup_type, lookup_value, parent_lookup_id, tenant_id, date_created) "
            + "VALUES (1275, 'MedAxis Home', 'https://console.medaxiscare.demo', ?, 2905, now())", homePages);
        sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, tenant_id) "
            + "VALUES (7300, 'worker', 'd', 'topic=scrapping-topic&partitions=[*]', 2905)");
        sql.update("INSERT INTO source_task (task_detail_id, task_name, task_status, source_task_type_id, tenant_id) "
            + "VALUES (7301, 't', 'Active', 7300, 2905)");
        sql.update("INSERT INTO source_task_payload (task_payload_id, tag_key, tag_value, payload_id) VALUES (1, 'bucket', 'b', 7301)");

        factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setDataSource(db.dataSource());
        factoryBean.setPackagesToScan("process.model.pojo");
        factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.enable_lazy_load_no_trans", "false");
        properties.put("hibernate.hbm2ddl.auto", "none");
        properties.put("hibernate.physical_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
        properties.put("hibernate.implicit_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy");
        factoryBean.setJpaPropertyMap(properties);
        factoryBean.afterPropertiesSet();
        EntityManagerFactory factory = factoryBean.getObject();
        EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(factory);
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(shared);
        lookups = repositories.getRepository(LookupDataRepository.class);
        tasks = repositories.getRepository(SourceTaskRepository.class);
    }

    @AfterAll
    static void drop() throws Exception {
        if (factoryBean != null) {
            factoryBean.destroy();
        }
        if (db != null) {
            db.close();
        }
    }

    /** The condition itself: the old read, detached, fails the moment it touches a lazy collection. */
    @Test
    void aDetachedLazyCollectionNoLongerLoads() {
        LookupData homePages = lookups.findByLookupType("PIPELINE_HOME_PAGES");

        assertThatThrownBy(() -> homePages.getChildren().size()).isInstanceOf(LazyInitializationException.class);
    }

    /** LookupDataCacheService's startup rebuild runs from @PostConstruct, with no transaction around it. */
    @Test
    void theLookupCacheRebuildsWithItsChildrenOutsideATransaction() {
        LookupDataCacheService cache = new LookupDataCacheService(lookups, null);

        cache.initialize();

        LookupDataDto homePages = cache.getParentLookupById("PIPELINE_HOME_PAGES");
        assertThat(homePages).as("the rebuild failed and left the cache empty").isNotNull();
        assertThat(homePages.getChildren()).extracting(LookupDataDto::getLookupType).containsExactly("MedAxis Home");
        assertThat(cache.getParentLookupById("TASK_GROUPS")).as("a family with no children yet").isNotNull();
        // QUEUE_FETCH_LIMIT left lookup_data for orchestration_setting (V88, MIG-136).
        assertThat(cache.getParentLookupById("QUEUE_FETCH_LIMIT")).isNull();
    }

    /** SettingServiceImpl.fetchSubLookupByParentId: the children of one family, read by query, not by association. */
    @Test
    void aFamilysChildrenAreReadWithoutTheAssociation() {
        List<LookupData> children = lookups.findChildrenOf(homePages);

        assertThat(children).extracting(LookupData::getLookupType).containsExactly("MedAxis Home");
    }

    /** SourceTaskServiceImpl.fetchSourceTaskWithSourceTaskId: the task and its tag rows in one read. */
    @Test
    void aTaskIsReadWithItsPayloadRows() {
        Optional<SourceTask> task = tasks.findWithPayloadByTaskDetailId(7301L);

        assertThat(task).isPresent();
        assertThat(task.get().getSourceTaskPayload()).extracting(row -> row.getTagKey()).containsExactly("bucket");
    }
}
