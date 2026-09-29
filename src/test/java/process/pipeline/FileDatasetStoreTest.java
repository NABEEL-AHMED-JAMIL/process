package process.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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
}
