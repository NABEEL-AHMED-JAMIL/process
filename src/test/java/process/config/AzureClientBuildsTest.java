package process.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.service.ObjectStorageService;
import process.util.EncryptionUtil;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An Azure storage connection could not be used at all: building its client threw
 * NoClassDefFoundError: reactor/util/context/ContextView. The Azure SDK (azure-core 1.47, over
 * reactor-netty 1.0) needs Reactor 3.4, and Spring Boot 2.3's dependency management pinned
 * reactor-core to 3.3.8 for everything -- so the jar shipped with a Reactor the Azure SDK cannot run
 * on, and browsing an Azure bucket answered 500. Found by the storage adapter contract suite
 * (MIG-160). Building a client touches no network, so this needs no Azure to run.
 */
class AzureClientBuildsTest {

    @Test
    void anAzureConnectionBuildsItsClient() {
        EncryptionUtil encryption = new EncryptionUtil();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        ReflectionTestUtils.setField(encryption, "base64Key", Base64.getEncoder().encodeToString(key));
        StorageConnection azure = new StorageConnection();
        azure.setStorageConnectionId(1L);
        azure.setAlias("azure-docs");
        azure.setProvider(StorageProvider.AZURE);
        azure.setStatus(Status.Active);
        azure.setAzureConnectionStringEnc(encryption.encrypt("DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
            + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
            + "BlobEndpoint=http://127.0.0.1:10000/devstoreaccount1;"));

        ObjectStorageService client = new StorageClientFactory(encryption, null).buildUncached(azure);

        assertThat(client).isNotNull();
    }
}
