package process.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import process.model.dto.FileChatMessageRequestDto;
import process.model.dto.FileChatPrepareRequestDto;
import process.model.enums.KafkaSecretKind;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static process.tenancy.CoreProbeFixture.*;

/**
 * MIG-166's CI cross-tenant probe for Core, the two places a request names a stored object: file chat
 * (/fileChat.json -- a bucket, a key and an AI agent) and Kafka key material (/kafkaSecret.json -- object keys in
 * the platform's config bucket) -- called by workspace A's tenant user and tenant admin with B's bucket, B's agent
 * and a person of B's uploaded certificates, against a real etl_job (CoreProbeFixture).
 *
 * The owners of these decisions are elsewhere, and the probe shows Core defers to them rather than deciding for
 * itself: whose bucket it is, storage-service answers -- file chat asks it, as the signed-in caller, for the
 * caller's buckets (FileChatServiceImpl.validateBucketAccess) and names B's to storage never; whose agent it is, the
 * AI service answers, and its refusal reaches the caller before any prompt is sent. Kafka key material is Core's own
 * rule (KafkaSecretServiceImpl.canUseObject): a person's uploads, and a tenant admin's reach over the tenant users
 * of their own workspace -- never over B's people -- checked before anything is read through the trusted path.
 * Opt-in, like every ScratchPostgres test.
 */
class CoreCrossTenantProbeFileChatAndSecretsPostgresTest {

    private static CoreProbeFixture fx;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_filechat");
    }

    @AfterAll
    static void drop() throws Exception {
        if (fx != null) {
            fx.close();
        }
    }

    @BeforeEach
    void forgetEarlierCalls() {
        fx.reset();
    }

    private static FileChatPrepareRequestDto open(String bucket, long aiAgentId) {
        FileChatPrepareRequestDto dto = new FileChatPrepareRequestDto();
        dto.setBucket(bucket);
        dto.setKey("reports/report.csv");
        dto.setAiAgentId(aiAgentId);
        return dto;
    }

    private static FileChatMessageRequestDto ask(String bucket, long aiAgentId) {
        FileChatMessageRequestDto dto = new FileChatMessageRequestDto();
        dto.setBucket(bucket);
        dto.setKey("reports/report.csv");
        dto.setAiAgentId(aiAgentId);
        dto.setMessage("summarise this file");
        return dto;
    }

    /** A key of the caller's own shape: kafka-secrets/{appUserId}/{uploadId}/{day}/{file}. */
    private static String ownKey(long appUserId, String file) {
        return "kafka-secrets/" + appUserId + "/acme01/2026-09-20/" + file;
    }

    @Test
    void noFileChatOrKeyMaterialEndpointReachesAnotherWorkspacesObjectsOrAgent() throws Exception {
        String before = fx.foreignRows();

        // ---- /fileChat.json: B's bucket, as the tenant user and the tenant admin
        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            assertThat(fx.probe("POST fileChat.json/prepareContext", caller, () -> fx.fileChat.prepareContext(open(B_BUCKET, A_AGENT))))
                .contains(REFUSED);
            assertThat(fx.probe("POST fileChat.json/endSession", caller, () -> fx.fileChat.endSession(open(B_BUCKET, A_AGENT))))
                .contains(REFUSED);
            assertThat(fx.probe("POST fileChat.json/sendMessage", caller, () -> fx.fileChat.sendMessage(ask(B_BUCKET, A_AGENT))))
                .contains(REFUSED);
            // A's own bucket with B's agent: the AI service says whose agent it is, and nothing is asked of it.
            assertThat(fx.probe("POST fileChat.json/sendMessage(their agent)", caller, () -> fx.fileChat.sendMessage(ask(A_BUCKET, B_AGENT))))
                .contains(REFUSED);
        }
        assertThat(fx.storage.naming(B_BUCKET)).as("B's bucket was never named to storage: A's own list said no first").isEmpty();
        assertThat(fx.storage.seen).as("storage was asked as the signed-in caller, never as the service")
            .isNotEmpty().allSatisfy(request -> {
                assertThat(request.authorization).isIn(USER_OF_A.bearer(), ADMIN_OF_A.bearer());
                assertThat(request.internalToken).isNull();
            });
        verify(fx.agents, never()).processAdHoc(any());
        verify(fx.agents, never()).resolveRuntimeConfig(eq(A_AGENT));
        verifyNoInteractions(fx.media, fx.rag, fx.embeddings, fx.indexLock);

        // ---- /kafkaSecret.json: key material a person of B's uploaded, alone and beside the caller's own
        assertThat(fx.probe("POST kafkaSecret.json/generateTruststore", ADMIN_OF_A,
            () -> fx.kafkaSecrets.generateTruststore(Collections.singletonList(B_SECRET_KEY)))).contains(REFUSED);
        assertThat(fx.probe("POST kafkaSecret.json/generateTruststore(mine and theirs)", ADMIN_OF_A,
            () -> fx.kafkaSecrets.generateTruststore(Arrays.asList(ownKey(ADMIN_A, "ca.pem"), B_SECRET_KEY)))).contains(REFUSED);
        assertThat(fx.probe("POST kafkaSecret.json/generateKeystore", ADMIN_OF_A,
            () -> fx.kafkaSecrets.generateKeystore(B_SECRET_KEY, B_PRIVATE_KEY))).contains(REFUSED);
        assertThat(fx.probe("POST kafkaSecret.json/generateKeystore(my certificate, their key)", ADMIN_OF_A,
            () -> fx.kafkaSecrets.generateKeystore(ownKey(ADMIN_A, "client.pem"), B_PRIVATE_KEY))).contains(REFUSED);

        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).as("nothing of B's or the platform's changed").isEqualTo(before);
        verify(fx.trustedStorage, never()).readForWorkflow(any(), any(), eq(B_SECRET_KEY));
        verify(fx.trustedStorage, never()).readForWorkflow(any(), any(), eq(B_PRIVATE_KEY));
        verify(fx.trustedStorage, never()).uploadForWorkflow(any(), any(), anyString(), any(), anyLong(), any());
    }

    /**
     * An upload names no key: it is filed under the uploader's own folder whatever the file is called, so there is
     * nothing to aim at B's -- and the probe shows where it went.
     */
    @Test
    void keyMaterialTheCallerUploadsLandsInTheirOwnFolder() throws Exception {
        MockMultipartFile store = new MockMultipartFile("file", "../" + USER_B + "/bravo01/truststore.p12", "application/x-pkcs12",
            "a pre-built store".getBytes(StandardCharsets.UTF_8));
        assertThat(fx.probe("POST kafkaSecret.json/uploadSecret", ADMIN_OF_A,
            () -> fx.kafkaSecrets.uploadSecret(store, KafkaSecretKind.TRUSTSTORE))).contains(SUCCEEDED);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(fx.trustedStorage).uploadForWorkflow(any(), eq(CONFIG_BUCKET), key.capture(), any(InputStream.class), anyLong(), any());
        // The caller's own folder, and the file name reduced to one safe segment: no way up into B's person's folder.
        assertThat(key.getValue()).startsWith("kafka-secrets/" + ADMIN_A + "/").doesNotContain("..");
        assertThat(key.getValue().split("/")).hasSize(5);
        assertThat(fx.leaks).isEmpty();
    }

    /** A caller with no workspace -- none named, or 0 or -1 -- is listed no bucket by storage and reaches no one's key material. */
    @Test
    void aCallerWithNoWorkspaceReachesNoOnesObjects() throws Exception {
        for (Long none : NO_WORKSPACE) {
            assertThat(fx.probe("POST fileChat.json/prepareContext", tenantlessUser(none),
                () -> fx.fileChat.prepareContext(open(A_BUCKET, A_AGENT)))).contains(REFUSED);
            assertThat(fx.probe("POST kafkaSecret.json/generateTruststore", tenantlessAdmin(none),
                () -> fx.kafkaSecrets.generateTruststore(Collections.singletonList(ownKey(USER_A, "ca.pem"))))).contains(REFUSED);
        }
        assertThat(fx.leaks).isEmpty();
        verify(fx.trustedStorage, never()).readForWorkflow(any(), any(), anyString());
        verifyNoInteractions(fx.media, fx.agents);
    }
}
