package process.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Datasets on the local disk of the replica that runs the run (MIG-230). A run's steps all run on one replica, in one
 * thread, so the next step always finds the previous one's output here; a retried run starts again from its first step,
 * wherever it runs. The platform bucket is where they belong once storage-service accepts a trusted caller for them
 * (TrustedCaller is a decision recorded there and in its boundary test); the key is already the bucket key, so moving
 * them changes this class and nothing else.
 *
 * {@code process.pipeline.datasets.dir}, default the JVM's temporary directory's etl-datasets.
 */
@Component
public class FileDatasetStore implements DatasetStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;

    public FileDatasetStore(@Value("${process.pipeline.datasets.dir:}") String dir) {
        String base = dir == null || dir.trim().isEmpty()
            ? Paths.get(System.getProperty("java.io.tmpdir"), "etl-datasets").toString() : dir.trim();
        this.root = Paths.get(base).toAbsolutePath().normalize();
    }

    @Override
    public void write(String storageKey, Dataset dataset) throws IOException {
        Path file = this.resolve(storageKey);
        Files.createDirectories(file.getParent());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("columns", dataset.getColumns());
        document.put("rows", dataset.getRows());
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        try (OutputStream out = Files.newOutputStream(partial)) {
            JSON.writeValue(out, document);
        }
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public Dataset read(String storageKey) throws IOException {
        try (InputStream in = Files.newInputStream(this.resolve(storageKey))) {
            Map<String, Object> document = JSON.readValue(in, new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            List<String> columns = (List<String>) document.getOrDefault("columns", new ArrayList<>());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) document.getOrDefault("rows", new ArrayList<>());
            return new Dataset(columns, rows);
        }
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(this.resolve(storageKey));
    }

    /** The key under the root, and only under it. */
    private Path resolve(String storageKey) {
        if (storageKey == null || !storageKey.startsWith("datasets/")) {
            throw new IllegalArgumentException("Not a dataset key: " + storageKey);
        }
        Path file = this.root.resolve(storageKey).normalize();
        if (!file.startsWith(this.root)) {
            throw new IllegalArgumentException("Not a dataset key: " + storageKey);
        }
        return file;
    }

    Path root() {
        return this.root;
    }
}
