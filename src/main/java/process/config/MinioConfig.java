package process.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * @author Nabeel Ahmed
 * */
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
        // Handed to credentials() unchecked, a missing key builds an anonymous client instead of
        // failing: the bucket then answers only if it is world-readable, and what surfaces is a
        // 403 on the first object rather than the configuration mistake behind it. Say so here.
        if (this.accessKey == null || this.accessKey.trim().isEmpty()
            || this.secretKey == null || this.secretKey.trim().isEmpty()) {
            throw new IllegalStateException(
                "MINIO_ACCESS_KEY and MINIO_SECRET_KEY are not configured; the MINIO provider "
                    + "will not fall back to an anonymous client.");
        }
        return MinioClient.builder()
            .endpoint(this.endpoint)
            .credentials(this.accessKey, this.secretKey)
            .build();
    }

}
