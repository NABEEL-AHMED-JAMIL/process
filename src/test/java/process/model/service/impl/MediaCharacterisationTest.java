package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import process.model.dto.ArchiveEntryDto;
import process.model.dto.BucketSummaryDto;
import process.model.dto.FileShareRequestDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ResponseDto;
import process.model.dto.TablePreviewDto;
import process.model.service.FileChatExtractionService;
import process.model.service.StorageBrowserService;
import process.notifications.NotificationPort;
import process.security.TenantContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Media & Documents, pinned before it moves (MIG-39): the ceilings and reader rules that are not
 * already pinned by ObjectPreviewServiceImplTest, ObjectTextServiceImplTest and
 * AudioTranscriptBucketGuardTest -- and the rule the move is most likely to break, that the
 * authorising metadata read comes before a single byte (ADR-012 keeps it with Storage).
 *
 * Written against the unmodified code; each is a frozen contract the extracted service must pass.
 */
class MediaCharacterisationTest {

    private final StorageBrowserService storage = mock(StorageBrowserService.class);
    private final ObjectPreviewServiceImpl preview = new ObjectPreviewServiceImpl(this.storage, mock(FileChatExtractionService.class));

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void object(String key, byte[] bytes, long reportedSize) {
        when(this.storage.getObjectMetadata("b", key)).thenReturn(
            new ObjectMetadataDto(key, key, reportedSize, "2026-09-23T00:00:00Z", "e", "application/octet-stream", true));
        when(this.storage.downloadObject(eq("b"), eq(key), any(), any()))
            .thenAnswer(inv -> new ObjectContentDto(new ByteArrayInputStream(bytes), "application/octet-stream", bytes.length, key));
    }

    private static byte[] csv(int dataRows) {
        StringBuilder out = new StringBuilder("id,name\n");
        for (int i = 0; i < dataRows; i++) out.append(i).append(",row").append(i).append('\n');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---- preview ceilings ------------------------------------------------------------------------

    @Test
    void aPageIsAtMostFiveHundredRows() throws Exception {
        assertThat(ObjectPreviewServiceImpl.MAX_LIMIT).isEqualTo(500);
        byte[] bytes = csv(600);
        this.object("t.csv", bytes, bytes.length);

        TablePreviewDto page = this.preview.table("b", "t.csv", null, 0, 10_000);

        assertThat(page.getLimit()).isEqualTo(500);
        assertThat(page.getRows()).hasSize(500);
        assertThat(page.getTotalRows()).isEqualTo(600);
    }

    /** Past 200,000 rows the count stops: totalRows -1 and a note, not a count that never ends. */
    @Test
    void theRowCountStopsAtTwoHundredThousand() throws Exception {
        assertThat(ObjectPreviewServiceImpl.COUNT_CAP).isEqualTo(200_000L);
        byte[] exact = csv(200_000);
        this.object("exact.csv", exact, exact.length);
        byte[] over = csv(200_002);
        this.object("over.csv", over, over.length);

        assertThat(this.preview.table("b", "exact.csv", null, 0, 10).getTotalRows()).isEqualTo(200_000);
        TablePreviewDto capped = this.preview.table("b", "over.csv", null, 0, 10);
        assertThat(capped.getTotalRows()).isEqualTo(-1);
        assertThat(capped.getNote()).isEqualTo("More than 200,000 rows; the count stopped there.");
        assertThat(capped.getRows()).hasSize(10);
    }

    @Test
    void aTableOverTwoHundredMebibytesIsRefusedBeforeItIsRead() {
        assertThat(ObjectPreviewServiceImpl.MAX_TABLE_BYTES).isEqualTo(200L * 1024 * 1024);
        assertThat(ObjectPreviewServiceImpl.MAX_WORKBOOK_BYTES).isEqualTo(25L * 1024 * 1024);
        when(this.storage.getObjectMetadata("b", "big.csv")).thenReturn(new ObjectMetadataDto("big.csv", "big.csv",
            ObjectPreviewServiceImpl.MAX_TABLE_BYTES + 1, "", "e", "text/csv", true));

        assertThatThrownBy(() -> this.preview.table("b", "big.csv", null, 0, 10))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too large to preview").hasMessageContaining("download");
        verify(this.storage, never()).downloadObject(anyString(), anyString(), any(), any());
    }

    /** Cells are not trimmed: " a " is what the file says. */
    @Test
    void cellsKeepTheirSpaces() throws Exception {
        byte[] bytes = "id,name\n1,  padded  \n".getBytes(StandardCharsets.UTF_8);
        this.object("s.csv", bytes, bytes.length);

        assertThat(this.preview.table("b", "s.csv", null, 0, 10).getRows()).containsExactly(Arrays.asList("1", "  padded  "));
    }

    @Test
    void anArchiveListsAtMostTwoThousandEntries() throws Exception {
        assertThat(ObjectPreviewServiceImpl.MAX_ARCHIVE_ENTRIES).isEqualTo(2000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < 2005; i++) {
                zip.putNextEntry(new ZipEntry("e" + i + ".txt"));
                zip.write('x');
                zip.closeEntry();
            }
        }
        this.object("many.zip", out.toByteArray(), out.size());

        assertThat(this.preview.archive("b", "many.zip")).hasSize(2000);
    }

    /** A streamed entry reports size -1 until it is read; it is read through and counted, not shown as unknown. */
    @Test
    void anEntryOfUnknownSizeIsReadThroughAndCounted() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] body = new byte[12_345];
        Arrays.fill(body, (byte) 'y');
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("streamed.bin"));   // DEFLATED: size sits after the data
            zip.write(body);
            zip.closeEntry();
        }
        this.object("s.zip", out.toByteArray(), out.size());

        List<ArchiveEntryDto> entries = this.preview.archive("b", "s.zip");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getSize()).isEqualTo(12_345L);
    }

    // ---- the rule the move must keep ---------------------------------------------------------------

    /** The metadata read is the authorisation: it happens first, and a refusal means no byte is fetched. */
    @Test
    void theAuthorisingMetadataReadComesBeforeAnyByte() throws Exception {
        byte[] bytes = csv(3);
        this.object("t.csv", bytes, bytes.length);

        this.preview.table("b", "t.csv", null, 0, 10);

        InOrder order = inOrder(this.storage);
        order.verify(this.storage).getObjectMetadata("b", "t.csv");
        order.verify(this.storage).downloadObject(eq("b"), eq("t.csv"), any(), any());
    }

    @Test
    void aRefusedMetadataReadFetchesNothing() {
        when(this.storage.getObjectMetadata("other-tenants-bucket", "t.csv"))
            .thenThrow(new IllegalArgumentException("Unknown bucket: other-tenants-bucket"));

        assertThatThrownBy(() -> this.preview.table("other-tenants-bucket", "t.csv", null, 0, 10))
            .hasMessageContaining("Unknown bucket");
        verify(this.storage, never()).downloadObject(anyString(), anyString(), any(), any());
    }

    // ---- file share ceilings ---------------------------------------------------------------------

    private ResponseDto shareSelection(int files, long bytesEach) throws Exception {
        TenantContext.set(2905L, "TENANT_USER", 10L, "ops@medaxis.example");
        when(this.storage.listBuckets()).thenReturn(Collections.singletonList(new BucketSummaryDto("B", "b", "S3")));
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < files; i++) {
            String key = "f" + i + ".csv";
            keys.add(key);
            when(this.storage.getObjectMetadata("b", key)).thenReturn(
                new ObjectMetadataDto(key, key, bytesEach, "", "e", "text/csv", true));
        }
        FileShareRequestDto request = new FileShareRequestDto();
        request.setBucket("b");
        request.setKeys(keys);
        request.setRecipientEmail("colleague@medaxis.example");
        return new FileShareServiceImpl(this.storage, mock(NotificationPort.class)).emailFile(request);
    }

    @Test
    void aShareOfMoreThanFiveHundredFilesIsRefused() throws Exception {
        ResponseDto refused = this.shareSelection(501, 1);

        assertThat(refused.getStatus()).isEqualTo("ERROR");
        assertThat(refused.getMessage()).contains("more than 500 files");
    }

    @Test
    void aShareOverTwentyMegabytesCombinedIsRefused() throws Exception {
        ResponseDto refused = this.shareSelection(2, 11L * 1024 * 1024);

        assertThat(refused.getStatus()).isEqualTo("ERROR");
        assertThat(refused.getMessage()).contains("larger than 20.0 MB combined");
    }
}
