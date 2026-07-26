package process.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3Config - builds the shared S3Client bean used by the Bucket Browser's S3 provider.
 * Credentials come from the environment (AWS_S3_ACCESS_KEY/SECRET_KEY/REGION), never
 * hardcoded. @Lazy so the app can start fine even if S3 isn't configured, as long as no
 * bucket actually resolves to the S3 provider.
 * @author Nabeel Ahmed
 */
@Configuration
public class S3Config {

    @Value("${aws.s3.access-key:}")
    private String accessKey;

    @Value("${aws.s3.secret-key:}")
    private String secretKey;

    @Value("${aws.s3.region:us-east-1}")
    private String region;

    @Lazy
    @Bean
    public S3Client s3Client() {
        if (this.accessKey == null || this.accessKey.trim().isEmpty()
            || this.secretKey == null || this.secretKey.trim().isEmpty()) {
            throw new IllegalStateException(
                "AWS_S3_ACCESS_KEY/AWS_S3_SECRET_KEY are not configured; cannot use a bucket mapped to the S3 provider.");
        }
        return S3Client.builder()
            .region(Region.of(this.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(this.accessKey, this.secretKey)))
            .build();
    }

}
