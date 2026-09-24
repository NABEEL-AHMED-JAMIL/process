package process.analytics;

import org.junit.jupiter.api.Test;
import process.model.dto.BrowseObjectsResponseDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ObjectSummaryDto;
import process.model.service.StorageBrowserService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-104: how many bytes a scan of a dataset reads -- what analytics.gb_scanned bills. DuckDB 1.1.3's
 * profiler reports rows scanned, not bytes, so the figure is Storage's: the object's size, or for a glob
 * the sum of the objects it matches, the way DuckDB expands it ('*' within one folder, '**' across them).
 */
class DatasetBytesTest {

    private final StorageBrowserService storage = mock(StorageBrowserService.class);
    private final DatasetBytes bytes = new DatasetBytes(this.storage);

    private static ObjectSummaryDto file(String key, long size) {
        return new ObjectSummaryDto(key.substring(key.lastIndexOf('/') + 1), key, false, size, null);
    }

    private static ObjectSummaryDto folder(String key) {
        return new ObjectSummaryDto(key, key, true, null, null);
    }

    private void listing(String prefix, ObjectSummaryDto... entries) {
        when(this.storage.listObjects(eq("sales"), eq(prefix), any(), anyInt()))
            .thenReturn(new BrowseObjectsResponseDto(new ArrayList<>(Arrays.asList(entries)), null));
    }

    @Test
    void oneFileIsItsSize() {
        ObjectMetadataDto meta = new ObjectMetadataDto();
        meta.setSize(5_368_709_120L);
        when(this.storage.getObjectMetadataCached("sales", "q3/orders.parquet")).thenReturn(meta);

        Optional<DatasetBytes.Size> size = this.bytes.of("sales", "q3/orders.parquet");

        assertThat(size).isPresent();
        assertThat(size.get().bytes).isEqualTo(5_368_709_120L);
        assertThat(size.get().objects).isEqualTo(1);
        assertThat(size.get().complete).isTrue();
    }

    @Test
    void aStarGlobSumsTheFilesItMatchesInItsOwnFolder() {
        this.listing("q3/", file("q3/a.csv", 100), file("q3/b.csv", 250), file("q3/notes.txt", 999), folder("q3/archive/"));

        DatasetBytes.Size size = this.bytes.of("sales", "q3/*.csv").get();

        assertThat(size.bytes).isEqualTo(350);
        assertThat(size.objects).isEqualTo(2);
        assertThat(size.complete).isTrue();
    }

    @Test
    void aDoubleStarGlobGoesDownEveryFolder() {
        this.listing("q3/", file("q3/a.csv", 100), folder("q3/archive/"));
        this.listing("q3/archive/", file("q3/archive/old.csv", 40), folder("q3/archive/2024/"));
        this.listing("q3/archive/2024/", file("q3/archive/2024/older.csv", 2));

        // As DuckDB 1.1.3 expands them (checked against glob()): '**' is any number of folders, none included.
        DatasetBytes.Size deep = this.bytes.of("sales", "q3/**/*.csv").get();
        assertThat(deep.bytes).isEqualTo(142);
        assertThat(deep.objects).isEqualTo(3);

        DatasetBytes.Size oneDown = this.bytes.of("sales", "q3/*/*.csv").get();
        assertThat(oneDown.bytes).isEqualTo(40);
        assertThat(oneDown.objects).isEqualTo(1);

        // A '*' after '**' still stops at a folder: archive/2024/older.csv is not archive/*.csv.
        DatasetBytes.Size named = this.bytes.of("sales", "q3/**/archive/*.csv").get();
        assertThat(named.bytes).isEqualTo(40);
        assertThat(named.objects).isEqualTo(1);
    }

    @Test
    void aGlobPastTheObjectCapIsMarkedPartial() {
        List<ObjectSummaryDto> many = new ArrayList<>();
        for (int i = 0; i < DatasetBytes.MAX_OBJECTS + 5; i++) {
            many.add(file("big/p" + i + ".parquet", 1));
        }
        when(this.storage.listObjects(eq("sales"), eq("big/"), any(), anyInt())).thenReturn(new BrowseObjectsResponseDto(many, null));

        DatasetBytes.Size size = this.bytes.of("sales", "big/*.parquet").get();

        assertThat(size.complete).isFalse();
        assertThat(size.objects).isEqualTo(DatasetBytes.MAX_OBJECTS);
    }

    /** A size that cannot be read is not guessed: nothing is billed rather than something wrong. */
    @Test
    void aSizeStorageCannotGiveIsNoSize() {
        when(this.storage.getObjectMetadataCached(anyString(), anyString())).thenThrow(new IllegalStateException("Storage could not be reached"));

        assertThat(this.bytes.of("sales", "q3/orders.parquet")).isEmpty();
        assertThat(this.bytes.of(null, "q3/orders.parquet")).isEmpty();
        assertThat(this.bytes.of("sales", " ")).isEmpty();
    }
}
