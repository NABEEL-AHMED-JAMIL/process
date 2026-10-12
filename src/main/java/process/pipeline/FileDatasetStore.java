package process.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import process.pipeline.data.RowSource;
import process.pipeline.data.RowsFile;

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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.io.BufferedOutputStream;
import java.util.Collection;

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
    /** How much readFile asks of the file at once: the JDK's per-thread temporary buffer stays this small. */
    private static final int READ_CHUNK = 1 << 16;

    private final Path root;

    public FileDatasetStore(@Value("${process.pipeline.datasets.dir:}") String dir) {
        String base = dir == null || dir.trim().isEmpty()
            ? Paths.get(System.getProperty("java.io.tmpdir"), "etl-datasets").toString() : dir.trim();
        this.root = Paths.get(base).toAbsolutePath().normalize();
    }

    @Override
    public void write(String storageKey, Dataset dataset) throws IOException {
        if (RowsFile.isRowsKey(storageKey)) {
            // MIG-344: a step that holds its output in memory writes it as a row file too.
            RowsFile.Writer out = RowsFile.create(this.resolve(storageKey));
            try {
                out.declare(dataset.getColumns());
                for (Map<String, Object> row : dataset.getRows()) {
                    out.add(row);
                }
                out.commit();
            } finally {
                out.abort();
            }
            return;
        }
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
    public Dataset read(String storageKey) throws Exception {
        if (RowsFile.isRowsKey(storageKey)) {
            try (RowSource rows = RowsFile.open(this.resolve(storageKey), RowSource.DEFAULT_BATCH)) {
                List<Map<String, Object>> all = new ArrayList<>((int) Math.min(Integer.MAX_VALUE - 8, rows.size()));
                for (List<Map<String, Object>> batch = rows.next(); batch != null; batch = rows.next()) {
                    all.addAll(batch);
                }
                return new Dataset(rows.columns(), all);
            }
        }
        try (InputStream in = Files.newInputStream(this.resolve(storageKey))) {
            Map<String, Object> document = JSON.readValue(in, new TypeReference<Map<String, Object>>() {});
            @SuppressWarnings("unchecked")
            List<String> columns = (List<String>) document.getOrDefault("columns", new ArrayList<>());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) document.getOrDefault("rows", new ArrayList<>());
            return new Dataset(columns, rows);
        }
    }

    // ---- MIG-344: datasets and files as streams ----------------------------------------------------------------------

    @Override
    public String outputKeyOf(long jobQueueId, int attempt, String stepKey, String name) {
        return DatasetStore.rowsKeyOf(jobQueueId, attempt, stepKey, name);
    }

    @Override
    public Output create(String storageKey) throws Exception {
        if (!RowsFile.isRowsKey(storageKey)) {
            return DatasetStore.super.create(storageKey);
        }
        RowsFile.Writer writer = RowsFile.create(this.resolve(storageKey));
        return new Output() {
            @Override
            public void declare(Collection<String> columns) {
                writer.declare(columns);
            }

            @Override
            public void add(Map<String, Object> row) throws IOException {
                writer.add(row);
            }

            @Override
            public long size() {
                return writer.size();
            }

            @Override
            public List<String> columns() {
                return writer.columns();
            }

            @Override
            public long commit() throws IOException {
                return writer.commit();
            }

            @Override
            public void abort() {
                writer.abort();
            }
        };
    }

    @Override
    public RowSource open(String storageKey, int batch) throws Exception {
        if (RowsFile.isRowsKey(storageKey)) {
            return RowsFile.open(this.resolve(storageKey), batch);
        }
        return DatasetStore.super.open(storageKey, batch);
    }

    @Override
    public FileOutput createFile(String storageKey) {
        Path file = this.resolve(storageKey);
        Path partial = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID().toString().substring(0, 8) + ".partial");
        OutputStream stream;
        try {
            Files.createDirectories(file.getParent());
            stream = new BufferedOutputStream(Files.newOutputStream(partial), 1 << 16);
        } catch (IOException ex) {
            throw new IllegalStateException("The file could not be started: " + ex.getMessage(), ex);
        }
        return new FileOutput() {
            private boolean done;

            @Override
            public OutputStream stream() {
                return stream;
            }

            @Override
            public void commit() throws IOException {
                stream.close();
                this.done = true;
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }

            @Override
            public void abort() {
                if (this.done) {
                    return;
                }
                this.done = true;
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // a file we are deleting
                }
                try {
                    Files.deleteIfExists(partial);
                } catch (IOException ignored) {
                    // the sweep takes what is left
                }
            }
        };
    }

    @Override
    public InputStream openFile(String storageKey) throws IOException {
        return Files.newInputStream(this.resolve(storageKey));
    }

    @Override
    public long fileSize(String storageKey) throws IOException {
        return Files.size(this.resolve(storageKey));
    }

    /** scratch/{run}/{attempt}/{step}/{try}: beside datasets/, never a dataset key. */
    @Override
    public Path scratch(long jobQueueId, int attempt, String stepKey) throws IOException {
        Path dir = this.root.resolve("scratch").resolve(String.valueOf(jobQueueId)).resolve(String.valueOf(attempt))
            .resolve(stepKey).resolve(UUID.randomUUID().toString().substring(0, 8)).normalize();
        if (!dir.startsWith(this.root.resolve("scratch"))) {
            throw new IllegalArgumentException("Not a step key: " + stepKey);
        }
        return Files.createDirectories(dir);
    }

    @Override
    public void removeScratch(long jobQueueId, int attempt) {
        Streams.deleteTree(this.root.resolve("scratch").resolve(String.valueOf(jobQueueId)).resolve(String.valueOf(attempt)));
        Path run = this.root.resolve("scratch").resolve(String.valueOf(jobQueueId));
        try {
            if (isEmpty(run)) {
                Files.deleteIfExists(run);
            }
        } catch (IOException ignored) {
            // the sweep takes it
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

    /**
     * The file's bytes, read in 64 KB chunks into one array of its size. Not Files.readAllBytes: that reads through a
     * FileChannel in one call the size of the file, and the JDK copies through a temporary direct buffer of that size which it
     * then keeps cached on the calling thread (jdk.nio.maxCachedBufferSize is unlimited by default). One json download of an
     * 815 MB kept file left 880 MB of native memory on a Tomcat thread for good, and process_app at 2.0 of its 2 GB (code
     * review 2026-10-07). The bytes returned are the same.
     */
    @Override
    public byte[] readFile(String storageKey) throws IOException {
        Path file = this.resolve(storageKey);
        long size = Files.size(file);
        if (size > Integer.MAX_VALUE - 8) {
            throw new IOException("The file is too large to read whole (" + size + " bytes).");
        }
        byte[] content = new byte[(int) size];
        int read = 0;
        try (InputStream in = Files.newInputStream(file)) {
            while (read < content.length) {
                int n = in.read(content, read, Math.min(READ_CHUNK, content.length - read));
                if (n < 0) {
                    break;
                }
                read += n;
            }
        }
        // A dataset file is written whole and moved into place (writeFile), so it never changes under a read.
        return read == content.length ? content : Arrays.copyOf(content, read);
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
        this.sweepScratch(olderThan);
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

    /** MIG-344: a run's scratch a crash left (a run that ends removes its own): every run folder not touched since the cutoff. */
    private void sweepScratch(Duration olderThan) throws IOException {
        Path top = this.root.resolve("scratch");
        if (!Files.isDirectory(top)) {
            return;
        }
        FileTime cutoff = FileTime.from(Instant.now().minus(olderThan));
        List<Path> runs;
        try (Stream<Path> list = Files.list(top)) {
            runs = list.collect(Collectors.toList());
        }
        for (Path run : runs) {
            boolean stale;
            try (Stream<Path> walk = Files.walk(run)) {
                stale = walk.allMatch(path -> {
                    try {
                        return Files.getLastModifiedTime(path).compareTo(cutoff) < 0;
                    } catch (IOException gone) {
                        return true;
                    }
                });
            }
            if (stale) {
                Streams.deleteTree(run);
            }
        }
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
