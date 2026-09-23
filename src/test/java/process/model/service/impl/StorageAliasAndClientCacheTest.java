package process.model.service.impl;

import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import process.config.StorageClientFactory;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.service.ObjectStorageService;
import process.model.service.StorageBrowserService;
import process.util.EncryptionUtil;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/**
 * Three Storage rules from MIG-160 that no backend is needed to show.
 */
class StorageAliasAndClientCacheTest {

    /**
     * BucketRewritingStorageService IGNORES every bucket argument it is given and uses the real bucket.
     * Callers keep sending the alias; the real bucket name is never exposed to, or accepted from, the
     * network. Asserted for every method of ObjectStorageService by reflection, so a method added to the
     * interface later cannot slip through with the alias (or fall to a default that refuses).
     */
    @Test
    void theAliasWrapperSendsTheRealBucketOnEveryOperation() throws Exception {
        ObjectStorageService delegate = mock(ObjectStorageService.class);
        BucketRewritingStorageService rewriting = new BucketRewritingStorageService(delegate, "real-bucket-7f3a");
        List<String> operations = new ArrayList<>();
        for (Method method : ObjectStorageService.class.getMethods()) {
            Object[] args = new Object[method.getParameterCount()];
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                args[i] = i == 0 ? "the-alias" : types[i] == int.class ? 10 : types[i] == long.class ? 10L
                    : types[i] == String.class ? "k" : types[i] == List.class ? Arrays.asList("k") : null;
            }
            method.invoke(rewriting, args);
            operations.add(method.getName());
        }
        List<Invocation> calls = new ArrayList<>(mockingDetails(delegate).getInvocations());
        assertThat(calls).hasSize(operations.size());
        for (Invocation call : calls) {
            assertThat(call.getArgument(0, Object.class)).as(call.getMethod().getName()).isEqualTo("real-bucket-7f3a");
        }
    }

    /**
     * The client cache is keyed on a fingerprint of the credentials, not the connection id: an edited
     * secret takes effect on the next call, with no restart and no stale client built from the old one.
     */
    @Test
    void anEditedSecretBuildsANewClientAndAnUnchangedOneIsReused() {
        EncryptionUtil encryption = new EncryptionUtil();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        ReflectionTestUtils.setField(encryption, "base64Key", Base64.getEncoder().encodeToString(key));
        StorageClientFactory factory = new StorageClientFactory(encryption, null);

        String stored = encryption.encrypt("first-secret");
        ObjectStorageService first = factory.serviceFor(minio(stored));
        ObjectStorageService again = factory.serviceFor(minio(stored));
        ObjectStorageService edited = factory.serviceFor(minio(encryption.encrypt("rotated-secret")));

        // The fingerprint is of what the row holds. The same row read twice reuses its client; a saved
        // edit writes new ciphertext and the next call builds from it. (Re-saving the SAME secret also
        // writes new ciphertext -- a fresh IV -- and costs one rebuild, which is harmless.)
        assertThat(again).as("same row, same values").isSameAs(first);
        assertThat(edited).as("the secret was edited").isNotSameAs(first);
    }

    /** DEF-122: the multipart uploadForWorkflow had no callers, and a trusted path nobody uses is only a door. */
    @Test
    void theUnusedMultipartWorkflowUploadIsGone() {
        for (Method method : StorageBrowserService.class.getMethods()) {
            if (method.getName().equals("uploadForWorkflow")) {
                assertThat(method.getParameterTypes()).as(method.toString()).doesNotContain(MultipartFile.class);
            }
        }
    }

    private static StorageConnection minio(String secretKeyEnc) {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(41L);
        connection.setAlias("docs");
        connection.setProvider(StorageProvider.MINIO);
        connection.setStatus(Status.Active);
        connection.setEndpoint("http://localhost:9000");
        connection.setAccessKey("docs-reader");
        connection.setSecretKeyEnc(secretKeyEnc);
        return connection;
    }
}
