package process.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Storage lives in storage-service (MIG-68); MIG-70 took it out of process. process reaches storage
 * only through process.storage.remote -- never a provider SDK, never the storage_connection table,
 * never a storage endpoint of its own. The old table stays for its retention period, read-only.
 */
class StorageLeftProcessTest {

    private static final Path MAIN = Paths.get("src", "main", "java");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Test
    void noProviderSdkButTheMailStagingsS3() throws IOException {
        Pattern sdk = Pattern.compile("^import (io\\.minio|com\\.azure\\.storage|org\\.apache\\.commons\\.net)\\.", Pattern.MULTILINE);
        List<String> found = new ArrayList<>();
        for (Path file : sources()) {
            if (sdk.matcher(read(file)).find()) {
                found.add(MAIN.relativize(file).toString());
            }
        }
        assertThat(found).isEmpty();
        String pom = read(Paths.get("pom.xml"));
        assertThat(pom).doesNotContain("<artifactId>minio</artifactId>").doesNotContain("<artifactId>azure-storage-blob</artifactId>")
            .doesNotContain("<artifactId>commons-net</artifactId>");
    }

    @Test
    void nothingReadsOrWritesTheStorageConnectionTable() throws IOException {
        Pattern entity = Pattern.compile("@Table\\(\\s*name\\s*=\\s*\"storage_connection\"");
        Pattern sql = Pattern.compile("(?i)(from|into|update|join)\\s+storage_connection\\b");
        List<String> found = new ArrayList<>();
        for (Path file : sources()) {
            String text = read(file);
            if (entity.matcher(text).find() || sql.matcher(text).find()) {
                found.add(MAIN.relativize(file).toString());
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void processServesNoStorageEndpoint() throws IOException {
        Pattern mapping = Pattern.compile("@RequestMapping\\(\\s*(value\\s*=\\s*)?\"/(storage|storageConnection)\\.json\"");
        List<String> found = new ArrayList<>();
        for (Path file : sources()) {
            if (mapping.matcher(read(file)).find()) {
                found.add(MAIN.relativize(file).toString());
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void theSwitchIsGoneStorageServiceIsTheOnlyWay() throws IOException {
        String config = read(MAIN.resolve("process/storage/remote/StorageRemoteConfig.java"));
        assertThat(config).doesNotContain("ConditionalOnProperty");
    }
}
