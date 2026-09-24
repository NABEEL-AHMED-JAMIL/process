package process.storage.remote;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import process.analytics.DatasetResolver;
import process.model.service.StorageBrowserService;
import process.model.service.impl.KafkaConnectionProfileServiceImpl;
import process.model.service.impl.TenantServiceImpl;
import process.storage.TrustedStorageOperations;
import process.util.EncryptionUtil;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * storage-service is process's only way to storage (MIG-70): no switch, no fallback. The beans are
 * always there, and every consumer of connection facts cannot be built without Storage's directory.
 */
class StorageRemoteConfigTest {

    @Test
    void everyStorageCallGoesToStorageServiceWithNoSwitchToTurn() {
        new ApplicationContextRunner().withUserConfiguration(StorageRemoteConfig.class)
            .withBean(EncryptionUtil.class, EncryptionUtil::new)
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(StorageServiceClient.class).hasSingleBean(RemoteStorageDirectory.class);
                assertThat(ctx.getBean(TrustedStorageOperations.class)).isInstanceOf(HttpTrustedStorage.class);
                assertThat(ctx.getBean(StorageBrowserService.class)).isInstanceOf(HttpStorageBrowser.class);
            });
    }

    @Test
    void theConsumersOfConnectionFactsRequireStoragesDirectory() {
        for (Class<?> consumer : Arrays.asList(DatasetResolver.class, TenantServiceImpl.class,
            KafkaConnectionProfileServiceImpl.class)) {
            Constructor<?>[] constructors = consumer.getConstructors();
            assertThat(constructors).as(consumer.getSimpleName()).hasSize(1);
            assertThat(constructors[0].getParameterTypes()).as(consumer.getSimpleName()).contains(RemoteStorageDirectory.class);
        }
    }

    @Test
    void aTrustedCallWithoutItsPrincipalIsNotMade() {
        HttpTrustedStorage trusted = new HttpTrustedStorage(new StorageServiceClient("http://127.0.0.1:1", "token"));
        assertThatThrownBy(() -> trusted.readForWorkflow(null, "etl-config", "k"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("principal");
    }
}
