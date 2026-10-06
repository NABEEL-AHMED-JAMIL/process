package process.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.row;

/** MIG-230: a run's datasets on the replica's disk, under their keys and nowhere else. */
class FileDatasetStoreTest {

    @TempDir
    Path dir;

    @Test
    void aDatasetIsWrittenUnderItsKeyAndReadBackAsItWas() throws Exception {
        FileDatasetStore store = new FileDatasetStore(this.dir.toString());
        String key = DatasetStore.keyOf(7401, 2, "shape", "output");
        assertThat(key).isEqualTo("datasets/7401/2/shape/output.json");
        Dataset written = new Dataset(Arrays.asList("id", "name", "empty"), Arrays.asList(row("id", 1, "name", "Ada", "empty", null),
            row("id", 2, "name", "Bo", "empty", null)));
        store.write(key, written);

        assertThat(Files.exists(this.dir.resolve(key))).isTrue();
        Dataset read = store.read(key);
        assertThat(read.getColumns()).containsExactly("id", "name", "empty");
        assertThat(read.getRows()).isEqualTo(written.getRows());
        assertThat(Files.list(this.dir.resolve("datasets/7401/2/shape"))).as("no partial file left behind").hasSize(1);

        store.delete(key);
        assertThat(Files.exists(this.dir.resolve(key))).isFalse();
        store.delete(key);
    }

    @Test
    void aKeyOutsideTheDatasetsIsRefused() {
        FileDatasetStore store = new FileDatasetStore(this.dir.toString());
        for (String key : new String[] {"../etc/passwd", "datasets/../../x.json", "/tmp/x.json", "config/secret.json", null}) {
            assertThatThrownBy(() -> store.read(key)).as(String.valueOf(key)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.write(key, Dataset.EMPTY)).as(String.valueOf(key)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theDefaultIsTheTemporaryDirectory() {
        assertThat(new FileDatasetStore("").root().toString()).endsWith("etl-datasets");
        assertThat(new FileDatasetStore(null).root().isAbsolute()).isTrue();
    }

    // MIG-214: what a failed write, a delete and a crash leave on the replica's disk.

    @Test
    void aWriteThatFailsLeavesNoPartialFileBehind() throws Exception {
        FileDatasetStore store = new FileDatasetStore(this.dir.toString());
        String key = DatasetStore.keyOf(7402, 1, "shape", "output");
        // Jackson cannot write a plain Object: the write fails after the partial file was opened.
        Dataset unwritable = new Dataset(Collections.singletonList("x"), Collections.singletonList(row("x", new Object())));

        assertThatThrownBy(() -> store.write(key, unwritable)).isInstanceOf(Exception.class);

        try (Stream<Path> left = Files.walk(this.dir)) {
            assertThat(left.filter(f -> f.toString().endsWith(".partial"))).isEmpty();
        }
        assertThat(Files.exists(this.dir.resolve(key))).isFalse();
    }

    @Test
    void deletingARunsLastDatasetRemovesItsEmptyFoldersButNotTheRoot() throws Exception {
        FileDatasetStore store = new FileDatasetStore(this.dir.toString());
        String kept = DatasetStore.keyOf(7403, 1, "read", "output");
        String gone = DatasetStore.keyOf(7404, 2, "shape", "output");
        store.write(kept, Dataset.EMPTY);
        store.write(gone, Dataset.EMPTY);

        store.delete(gone);

        assertThat(Files.exists(this.dir.resolve("datasets/7404"))).as("the run's folders go with its last dataset").isFalse();
        assertThat(Files.exists(this.dir.resolve(kept))).isTrue();
        assertThat(Files.isDirectory(this.dir.resolve("datasets"))).isTrue();
    }

    @Test
    void theLeftoverSweepRemovesStalePartialFilesAndEmptyFoldersButNotFreshOnes() throws Exception {
        FileDatasetStore store = new FileDatasetStore(this.dir.toString());
        Path stale = this.dir.resolve("datasets/7405/1/shape/output.json.partial");
        Path fresh = this.dir.resolve("datasets/7406/1/shape/output.json.partial");
        Path emptyRun = this.dir.resolve("datasets/7407/1/shape");
        Files.createDirectories(stale.getParent());
        Files.createDirectories(fresh.getParent());
        Files.createDirectories(emptyRun);
        Files.write(stale, new byte[] {1});
        Files.write(fresh, new byte[] {1});
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        Files.setLastModifiedTime(emptyRun, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        int removed = store.sweepLeftovers(Duration.ofHours(1));

        assertThat(removed).isEqualTo(1);
        assertThat(Files.exists(stale)).isFalse();
        assertThat(Files.exists(this.dir.resolve("datasets/7405"))).isFalse();
        assertThat(Files.exists(this.dir.resolve("datasets/7407"))).as("an old empty run folder").isFalse();
        assertThat(Files.exists(fresh)).as("a write in progress is left alone").isTrue();
    }
}
