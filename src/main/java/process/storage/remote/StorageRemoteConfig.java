package process.storage.remote;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import process.model.service.StorageBrowserService;
import process.storage.TrustedStorageOperations;
import process.util.EncryptionUtil;

/**
 * The switch (MIG-68 cutover): with storage.remote on, every storage call process makes goes to
 * storage-service -- the guarded ones as the signed-in user, the four trusted callers with the service
 * token, and the directory questions (DuckDB's credentials, counts, ids, sizes) likewise. Off, process
 * keeps reading its own table, as before.
 */
@Configuration
@ConditionalOnProperty(name = "storage.remote", havingValue = "true")
public class StorageRemoteConfig {

    @Bean
    public StorageServiceClient storageServiceClient(@Value("${storage.service-url:http://storage:9120}") String url,
        @Value("${internal.service-token:}") String token) {
        return new StorageServiceClient(url, token);
    }

    @Bean
    @Primary
    public TrustedStorageOperations httpTrustedStorage(StorageServiceClient storage) {
        return new HttpTrustedStorage(storage);
    }

    @Bean
    @Primary
    public StorageBrowserService httpStorageBrowser(StorageServiceClient storage) {
        return new HttpStorageBrowser(storage);
    }

    @Bean
    public RemoteStorageDirectory remoteStorageDirectory(StorageServiceClient storage, EncryptionUtil encryption) {
        return new RemoteStorageDirectory(storage, encryption);
    }
}
