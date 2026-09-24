package process.storage.remote;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import process.model.service.StorageBrowserService;
import process.storage.TrustedStorageOperations;

/**
 * process's only way to storage (MIG-70): every call goes to storage-service -- the guarded ones as
 * the signed-in user, the four trusted callers with the service token, and the directory questions
 * (DuckDB's credentials, counts, ids, sizes) likewise. There is no table and no adapter left here.
 */
@Configuration
public class StorageRemoteConfig {

    @Bean
    public StorageServiceClient storageServiceClient(@Value("${storage.service-url:http://storage:9120}") String url,
        @Value("${internal.service-token:}") String token) {
        return new StorageServiceClient(url, token);
    }

    @Bean
    public TrustedStorageOperations httpTrustedStorage(StorageServiceClient storage) {
        return new HttpTrustedStorage(storage);
    }

    @Bean
    public StorageBrowserService httpStorageBrowser(StorageServiceClient storage) {
        return new HttpStorageBrowser(storage);
    }

    @Bean
    public RemoteStorageDirectory remoteStorageDirectory(StorageServiceClient storage) {
        return new RemoteStorageDirectory(storage);
    }
}
