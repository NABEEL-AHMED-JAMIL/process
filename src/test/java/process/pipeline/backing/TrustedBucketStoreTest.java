package process.pipeline.backing;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import process.model.dto.ObjectContentDto;
import process.storage.TrustedAccess;
import process.storage.TrustedCaller;
import process.storage.TrustedStorageOperations;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** MIG-231: the bucket steps reach storage-service as CORE_PIPELINES in the run's workspace, and not before Storage accepts it. */
class TrustedBucketStoreTest {

    private final TrustedStorageOperations storage = mock(TrustedStorageOperations.class);

    @Test
    void offUntilStorageAcceptsTheCallerAndNothingIsSent() {
        TrustedBucketStore off = new TrustedBucketStore(this.storage, false);
        assertThat(off.unavailable()).hasValueSatisfying(reason -> assertThat(reason).contains("CORE_PIPELINES"));
        assertThatThrownBy(() -> off.list(41L, "lake", "", 10)).hasMessageContaining("trusted-caller is off");
        verifyNoInteractions(this.storage);
    }

    @Test
    void everyCallIsCorePipelinesInTheRunsWorkspace() throws Exception {
        TrustedBucketStore store = new TrustedBucketStore(this.storage, true);
        when(this.storage.listForWorkflow(any(), eq("lake"), eq("in/"), eq(10))).thenReturn(new TrustedStorageOperations.ObjectListing(
            Collections.singletonList(new TrustedStorageOperations.ListedObject("in/a.csv", 3)), true));
        when(this.storage.readForWorkflow(any(), eq("lake"), eq("in/a.csv"))).thenReturn(
            new ObjectContentDto(new ByteArrayInputStream(new byte[] {1, 2, 3}), "text/csv", 3, "a.csv"));

        BucketStore.Listing listing = store.list(41L, "lake", "in/", 10);
        assertThat(listing.objects).extracting(object -> object.key + ":" + object.size).containsExactly("in/a.csv:3");
        assertThat(listing.truncated).isTrue();
        assertThat(store.read(41L, "lake", "in/a.csv", 10)).containsExactly(1, 2, 3);
        store.upload(41L, "exports", "out.csv", new byte[] {9}, "text/csv");

        ArgumentCaptor<TrustedAccess> access = ArgumentCaptor.forClass(TrustedAccess.class);
        verify(this.storage).listForWorkflow(access.capture(), anyString(), anyString(), eq(10));
        verify(this.storage).uploadForWorkflow(access.capture(), eq("exports"), eq("out.csv"), any(InputStream.class), eq(1L), eq("text/csv"));
        assertThat(access.getAllValues()).allSatisfy(one -> {
            assertThat(one.getCaller()).isEqualTo(TrustedCaller.CORE_PIPELINES);
            assertThat(one.getTenantId()).isEqualTo(41L);
        });
    }

    @Test
    void anObjectLargerThanAStepReadsIsRefused() {
        TrustedBucketStore store = new TrustedBucketStore(this.storage, true);
        when(this.storage.readForWorkflow(any(), anyString(), anyString())).thenReturn(
            new ObjectContentDto(new ByteArrayInputStream(new byte[20]), "text/csv", 20, "big.csv"));
        assertThatThrownBy(() -> store.read(41L, "lake", "big.csv", 10)).hasMessageContaining("is 20 bytes; a step reads at most 10");
    }
}
