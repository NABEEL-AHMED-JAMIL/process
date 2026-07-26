package process.config;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * AzureBlobConfig - builds the shared BlobServiceClient bean used by the Bucket Browser's
 * Azure provider. Connection string comes from the environment
 * (AZURE_STORAGE_CONNECTION_STRING), never hardcoded. @Lazy so the app can start fine even
 * if Azure isn't configured, as long as no bucket actually resolves to the AZURE provider.
 * @author Nabeel Ahmed
 */
@Configuration
public class AzureBlobConfig {

    @Value("${azure.storage.connection-string:}")
    private String connectionString;

    @Lazy
    @Bean
    public BlobServiceClient blobServiceClient() {
        if (this.connectionString == null || this.connectionString.trim().isEmpty()) {
            throw new IllegalStateException(
                "AZURE_STORAGE_CONNECTION_STRING is not configured; cannot use a bucket mapped to the AZURE provider.");
        }
        return new BlobServiceClientBuilder()
            .connectionString(this.connectionString)
            .buildClient();
    }

}
