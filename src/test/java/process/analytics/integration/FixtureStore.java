package process.analytics.integration;

import process.model.dto.ObjectContentDto;
import process.model.pojo.StorageConnection;
import process.util.EncryptionUtil;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.InputStream;
import java.net.URI;

/**
 * The integration suites' own way to MinIO and LocalStack (both speak S3) since the storage adapters
 * left process with MIG-70: enough to see a bucket answer, see an object exist, put a fixture there and
 * read one back. The engine under test still reads through DuckDB, exactly as before.
 */
final class FixtureStore {

    private final S3Client s3;

    private FixtureStore(S3Client s3) {
        this.s3 = s3;
    }

    static FixtureStore of(StorageConnection connection, EncryptionUtil encryption) {
        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(connection.getRegion() == null ? "us-east-1" : connection.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(connection.getAccessKey(),
                encryption.decrypt(connection.getSecretKeyEnc()))));
        if (connection.getEndpoint() != null && !connection.getEndpoint().trim().isEmpty()) {
            builder.endpointOverride(URI.create(connection.getEndpoint().trim())).forcePathStyle(true);
        }
        return new FixtureStore(builder.build());
    }

    void listObjects(String bucket, String prefix, String continuationToken, int maxKeys) {
        this.s3.listObjectsV2(b -> b.bucket(bucket).prefix(prefix).maxKeys(maxKeys));
    }

    void getObjectMetadata(String bucket, String key) {
        this.s3.headObject(b -> b.bucket(bucket).key(key));
    }

    ObjectContentDto getObjectContent(String bucket, String key, Long rangeStart, Long rangeEnd) {
        ResponseInputStream<GetObjectResponse> object = this.s3.getObject(b -> b.bucket(bucket).key(key));
        GetObjectResponse response = object.response();
        return new ObjectContentDto(object, response.contentType(), response.contentLength(), key);
    }

    void uploadObject(String bucket, String key, InputStream content, long size, String contentType) {
        this.s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromInputStream(content, size));
    }
}
