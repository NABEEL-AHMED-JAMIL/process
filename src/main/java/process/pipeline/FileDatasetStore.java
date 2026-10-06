package process.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        try {
            try (OutputStream out = Files.newOutputStream(partial)) {
                JSON.writeValue(out, document);
            }
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failed) {
            // MIG-214: a write that fails part-way leaves nothing behind.
            Files.deleteIfExists(partial);
            throw failed;
        }
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
    public void writeFile(String storageKey, byte[] content) throws IOException {
        Path file = this.resolve(storageKey);
        Files.createDirectories(file.getParent());
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        try {
            Files.write(partial, content);
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(partial);
            throw failed;
        }
    }

    @Override
    public byte[] readFile(String storageKey) throws IOException {
        return Files.readAllBytes(this.resolve(storageKey));
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Path file = this.resolve(storageKey);
        Files.deleteIfExists(file);
        // MIG-214: the run's folders go with its last dataset (datasets/ itself stays).
        this.pruneEmpty(file.getParent());
    }

    /**
     * What a crash leaves (MIG-214): partial files older than {@code olderThan} -- a write in progress is younger -- and
     * empty folders, when they are that old or this sweep emptied them. Returns the partial files removed.
     */
    @Override
    public int sweepLeftovers(Duration olderThan) throws IOException {
        Path top = this.root.resolve("datasets");
        if (!Files.isDirectory(top)) {
            return 0;
        }
        FileTime cutoff = FileTime.from(Instant.now().minus(olderThan));
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(top)) {
            paths = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        int removed = 0;
        Set<Path> emptied = new HashSet<>();
        for (Path path : paths) {
            if (path.equals(top)) {
                continue;
            }
            if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".partial")
                && Files.getLastModifiedTime(path).compareTo(cutoff) < 0) {
                Files.deleteIfExists(path);
                emptied.add(path.getParent());
                removed++;
            } else if (Files.isDirectory(path) && isEmpty(path)
                && (emptied.contains(path) || Files.getLastModifiedTime(path).compareTo(cutoff) < 0)) {
                Files.deleteIfExists(path);
                emptied.add(path.getParent());
            }
        }
        return removed;
    }

    private void pruneEmpty(Path dir) throws IOException {
        Path top = this.root.resolve("datasets");
        while (dir != null && dir.startsWith(top) && !dir.equals(top) && isEmpty(dir)) {
            try {
                Files.delete(dir);
            } catch (DirectoryNotEmptyException | NoSuchFileException raced) {
                return;
            }
            dir = dir.getParent();
        }
    }

    private static boolean isEmpty(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return !entries.findAny().isPresent();
        }
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
