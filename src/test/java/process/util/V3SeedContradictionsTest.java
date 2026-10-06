package process.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contradictions B2 and B3 (MIG-87, MIG-100), made executable rather than narrative.
 *
 * B3: V3 seeded eleven source_task_type rows, 1000-1010, not seven. Four of them (1007-1010) carried a
 * queue_topic_partition that KafkaTopicPartitionUtil's pattern,
 * {@code ^topic=([a-zA-Z0-9._-]{1,249})&partitions=\[([0-9]+|\*)\]$}, rejects -- they could never have
 * dispatched a message, and they are the evidence behind V10's deleting them.
 *
 * B2: the V3 seed is not live routing configuration. V10 deleted 1001-1010; comparison-topic has no
 * consumer, producer or configuration anywhere; and V1-V49 no longer run at all since the V50 baseline, which
 * seeds no task type -- a task type has one owning tenant since V39, so the platform-wide seed rows could not
 * exist today anyway. Nothing re-seeds them: EtlJobChangelogPostgresTest checks a built database has none.
 */
class V3SeedContradictionsTest {

    /** V3__insert_source_task_type.sql, rows 1007-1010, verbatim. */
    private static final List<String> V3_ROWS_1007_TO_1010 = Arrays.asList(
        "extraction-topic&partitions=[*]", "Web Auto Bots", "Mobile Auto Bots", "Data Statistics Report");

    /** Rows 1000-1006, which do match -- so the pattern is not simply rejecting everything V3 wrote. */
    private static final List<String> V3_ROWS_1000_TO_1006 = Arrays.asList(
        "topic=test-topic&partitions=[*]", "topic=scrapping-topic&partitions=[0]", "topic=scrapping-topic&partitions=[1]",
        "topic=scrapping-topic&partitions=[2]", "topic=comparison-topic&partitions=[0]", "topic=comparison-topic&partitions=[1]",
        "topic=comparison-topic&partitions=[2]");

    @Test
    void theFourMalformedV3RowsCouldNeverDispatch() {
        for (String value : V3_ROWS_1007_TO_1010) {
            assertThat(KafkaTopicPartitionUtil.parse(value)).as(value).isEmpty();
        }
        for (String value : V3_ROWS_1000_TO_1006) {
            assertThat(KafkaTopicPartitionUtil.parse(value)).as(value).isPresent();
        }
        assertThat(V3_ROWS_1007_TO_1010.size() + V3_ROWS_1000_TO_1006.size()).as("V3 seeded eleven rows").isEqualTo(11);
    }

    @Test
    void theV3SeedFileSaysElevenRows() throws Exception {
        Path v3 = Paths.get("src/main/resources/db/changelog/changelog-sets/archive/V3.0-init/V3__insert_source_task_type.sql");
        String seed = new String(Files.readAllBytes(v3), StandardCharsets.UTF_8);

        assertThat(seed.split("INSERT INTO source_task_type", -1)).hasSize(12);
        for (String value : V3_ROWS_1007_TO_1010) {
            assertThat(seed).contains("'" + value + "'");
        }
    }

    @Test
    void comparisonTopicIsNowhereInLiveCodeOrConfiguration() throws Exception {
        List<String> found = new ArrayList<>();
        for (String root : new String[] {"src/main/java", "src/main/resources"}) {
            List<Path> files;
            try (Stream<Path> walk = Files.walk(Paths.get(root))) {
                files = walk.filter(Files::isRegularFile).filter(f -> !f.toString().contains("/archive/"))
                    .filter(f -> !f.toString().endsWith(".xlsx")).collect(Collectors.toList());
            }
            for (Path file : files) {
                if (new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("comparison-topic")) {
                    found.add(file.toString());
                }
            }
        }
        assertThat(found).isEmpty();
    }
}
