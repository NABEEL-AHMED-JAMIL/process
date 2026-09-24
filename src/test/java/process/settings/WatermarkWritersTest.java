package process.settings;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-167, owner's D4: the two watermarks are written only by their own crons, enforced in the application and pinned
 * here. SCHEDULER_LAST_RUN_TIME only by the enqueuer's start-up (ModelApplication), AUDIT_LOG_SYNC_LAST_RUN_TIME only
 * by AuditLogSyncCron, and orchestration_setting is written by nothing but OrchestrationSettings itself -- so the
 * settings API (EngineSettingsService, which refuses both keys) has no other door to them.
 */
class WatermarkWritersTest {

    private static final Path MAIN = Paths.get("src/main/java");

    @Test
    void eachWatermarkHasExactlyItsOwnWriter() throws IOException {
        assertThat(callersOf("writeWatermark(IfAbsent)?\\(\\s*Watermark\\.SCHEDULER_LAST_RUN_TIME"))
            .containsExactly("process/ModelApplication.java");
        assertThat(callersOf("writeWatermark(IfAbsent)?\\(\\s*Watermark\\.AUDIT_LOG_SYNC_LAST_RUN_TIME"))
            .containsExactly("process/engine/cron/AuditLogSyncCron.java");
        assertThat(callersOf("\\.writeWatermark(IfAbsent)?\\(")).containsExactlyInAnyOrder(
            "process/ModelApplication.java", "process/engine/cron/AuditLogSyncCron.java");
    }

    @Test
    void nothingElseWritesOrchestrationSetting() throws IOException {
        List<String> writers = new ArrayList<>();
        for (Path source : sources()) {
            String text = read(source).toLowerCase();
            if (text.matches("(?s).*(insert\\s+into|update|delete\\s+from)\\s+orchestration_setting.*")) {
                writers.add(relative(source));
            }
        }
        assertThat(writers).containsExactly("process/settings/OrchestrationSettings.java");
    }

    @Test
    void theSettingsApiHasNoWatermarkDoor() throws IOException {
        String service = read(MAIN.resolve("process/settings/EngineSettingsService.java"));
        assertThat(service).doesNotContain("writeWatermark");
    }

    private static List<String> callersOf(String regex) throws IOException {
        Pattern call = Pattern.compile(regex);
        return sources().stream()
            .filter(source -> !relative(source).equals("process/settings/OrchestrationSettings.java"))
            .filter(source -> call.matcher(read(source)).find())
            .map(WatermarkWritersTest::relative)
            .collect(Collectors.toList());
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(path -> path.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
    }

    private static String relative(Path source) {
        return MAIN.relativize(source).toString().replace('\\', '/');
    }

    private static String read(Path source) {
        try {
            return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new IllegalStateException(failed);
        }
    }
}
