package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import process.model.dto.ResponseDto;
import process.model.repository.AppUserRepository;
import process.model.service.KafkaSecretService;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import process.util.KafkaSecretPath;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.SUCCESS;

/**
 * A key the caller owns, pointing at an object that is no longer there.
 *
 * canUseObject settles whose key it is by reading the user id out of it, which it can do without
 * touching the bucket -- so a certificate deleted underneath a console that still lists it passes
 * the guard and fails at the read. The storage provider raises its own RuntimeException for that,
 * and every one of these calls is wrapped by a controller that turns anything unhandled into a 500
 * carrying no message at all: the caller was told nothing about the one failure they could fix.
 *
 * @author Nabeel Ahmed
 */
class KafkaSecretUnreadableObjectTest {

    private static final Long OWNER = 1248L;
    private static final Long TENANT = 5L;

    private final StorageBrowserService storageBrowserService = mock(StorageBrowserService.class);
    private final KafkaSecretService service =
        new KafkaSecretServiceImpl(this.storageBrowserService, mock(AppUserRepository.class), null);

    private final String ownersKey =
        KafkaSecretPath.newUpload(OWNER, "ca.pem", LocalDate.of(2026, 8, 31)).key();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void signedInAsTheOwner() {
        TenantContext.set(TENANT, "TENANT_USER", OWNER, "owner@example.com");
    }

    private void assertRefusedWithSomethingToActOn(ResponseDto response) throws Exception {
        assertThat(response.getStatus()).isNotEqualTo(SUCCESS);
        assertThat(response.getMessage()).contains("could not be read from storage");
        // And no half-built store was left behind by the attempt.
        verify(this.storageBrowserService, never())
            .uploadForWorkflow(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
    }

    /** What MinIO, S3 and Azure all do for a key that is not there: raise, with a provider message. */
    @Test
    void aCertificateThatIsGoneIsReportedRatherThanThrown() throws Exception {
        when(this.storageBrowserService.readForWorkflow(anyString(), anyString()))
            .thenThrow(new RuntimeException("Could not fetch object content etl-bucket/" + this.ownersKey));
        this.signedInAsTheOwner();

        this.assertRefusedWithSomethingToActOn(
            this.service.generateTruststore(Collections.singletonList(this.ownersKey)));
    }

    /** And a storage layer that answers with nothing at all, which used to be a NullPointerException. */
    @Test
    void anEmptyAnswerFromStorageIsReportedTheSameWay() throws Exception {
        when(this.storageBrowserService.readForWorkflow(anyString(), anyString())).thenReturn(null);
        this.signedInAsTheOwner();

        this.assertRefusedWithSomethingToActOn(
            this.service.generateKeystore(this.ownersKey, this.ownersKey));
    }

}
