package process.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import java.net.URI;

/**
 * The S3 client behind the legacy BUCKET_LIST path -- a lookup entry mapped to S3 that has not
 * been turned into a storage connection. Buckets that are connections build their own client in
 * StorageClientFactory from the credentials on the row; this one signs with the platform's
 * shared aws.* identity, the same pair SES uses. Lazy, so a deployment with no such lookup never
 * needs the keys at all.
 *
 * @author Nabeel Ahmed
 * */
@Configuration
public class S3Config {

    @Value("${aws.access-key:}")
    private String accessKey;

    @Value("${aws.secret-key:}")
    private String secretKey;

    @Value("${aws.region:us-east-1}")
    private String region;

    /** LocalStack for local work; blank everywhere else, and the SDK finds the regional endpoint. */
    @Value("${aws.endpoint:}")
    private String endpoint;

    @Lazy
    @Bean
    public S3Client s3Client() {
        if (this.accessKey == null || this.accessKey.trim().isEmpty()
            || this.secretKey == null || this.secretKey.trim().isEmpty()) {
            throw new IllegalStateException(
                "AWS_ACCESS_KEY/AWS_SECRET_KEY are not configured; cannot use a bucket mapped to the S3 provider.");
        }
        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(this.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(this.accessKey, this.secretKey)));
        if (this.endpoint != null && !this.endpoint.trim().isEmpty()) {
            // An emulator answers on one host for every bucket, so the bucket has to stay in the
            // path rather than become a virtual host the emulator has never heard of.
            builder = builder.endpointOverride(URI.create(this.endpoint.trim())).forcePathStyle(true);
        }
        return builder.build();
    }
}
