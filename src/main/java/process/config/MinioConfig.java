package process.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * MinioConfig - builds the shared MinioClient bean used by the Bucket Browser's MinIO
 * provider. Credentials come from the environment (MINIO_ENDPOINT/ACCESS_KEY/SECRET_KEY),
 * never hardcoded. @Lazy so the app can start fine even if MinIO isn't configured, as long
 * as no bucket actually resolves to the MINIO provider.
 * @author Nabeel Ahmed
 */
@Configuration
public class MinioConfig {

    @Value("${minio.endpoint:}")
    private String endpoint;

    @Value("${minio.access-key:}")
    private String accessKey;

    @Value("${minio.secret-key:}")
    private String secretKey;

    @Lazy
    @Bean
    public MinioClient minioClient() {
        if (this.endpoint == null || this.endpoint.trim().isEmpty()) {
            throw new IllegalStateException(
                "MINIO_ENDPOINT is not configured; cannot use a bucket mapped to the MINIO provider.");
        }
        return MinioClient.builder()
            .endpoint(this.endpoint)
            .credentials(this.accessKey, this.secretKey)
            .build();
    }

}
