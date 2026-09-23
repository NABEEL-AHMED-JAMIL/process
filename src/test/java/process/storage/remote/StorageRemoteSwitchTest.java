package process.storage.remote;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import process.analytics.DatasetResolver;
import process.config.StorageConnectionBootstrap;
import process.engine.cron.UsageMeasurerCron;
import process.model.service.StorageBrowserService;
import process.model.service.impl.KafkaConnectionProfileServiceImpl;
import process.model.service.impl.TenantServiceImpl;
import process.storage.JdbcConnectionIdResolver;
import process.storage.TrustedStorageOperations;
import process.util.EncryptionUtil;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * storage.remote is the cutover's one switch (MIG-68): on, every storage call goes to storage-service
 * and process's own table-readers stand down; off -- the default -- nothing changes.
 */
class StorageRemoteSwitchTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(StorageRemoteConfig.class)
        .withBean(EncryptionUtil.class, EncryptionUtil::new);

    @Test
    void onEveryStorageCallGoesToStorageService() {
        this.context.withPropertyValues("storage.remote=true", "storage.service-url=http://storage:9120").run(ctx -> {
            assertThat(ctx).hasSingleBean(StorageServiceClient.class).hasSingleBean(RemoteStorageDirectory.class);
            assertThat(ctx.getBean(TrustedStorageOperations.class)).isInstanceOf(HttpTrustedStorage.class);
            assertThat(ctx.getBean(StorageBrowserService.class)).isInstanceOf(HttpStorageBrowser.class);
        });
    }

    @Test
    void offByDefaultNothingChanges() {
        this.context.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(StorageServiceClient.class).doesNotHaveBean(RemoteStorageDirectory.class)
                .doesNotHaveBean(HttpTrustedStorage.class).doesNotHaveBean(HttpStorageBrowser.class);
        });
    }

    /** Two things that write or read process's table directly must not run once Storage owns it. */
    @Test
    void theTableReadersStandDownWhenItIsOn() {
        for (Class<?> standsDown : Arrays.asList(JdbcConnectionIdResolver.class, StorageConnectionBootstrap.class)) {
            ConditionalOnProperty condition = standsDown.getAnnotation(ConditionalOnProperty.class);
            assertThat(condition).as(standsDown.getSimpleName()).isNotNull();
            assertThat(condition.name()).containsExactly("storage.remote");
            assertThat(condition.havingValue()).isEqualTo("false");
            assertThat(condition.matchIfMissing()).isTrue();
        }
    }

    /** And every consumer that reads it takes the remote directory when there is one. */
    @Test
    void everyTableConsumerCanBeHandedTheRemoteDirectory() throws Exception {
        for (Class<?> consumer : Arrays.asList(DatasetResolver.class, UsageMeasurerCron.class, TenantServiceImpl.class,
            KafkaConnectionProfileServiceImpl.class)) {
            Field remote = consumer.getDeclaredField("remote");
            assertThat(remote.getType()).as(consumer.getSimpleName()).isEqualTo(RemoteStorageDirectory.class);
        }
    }
}
