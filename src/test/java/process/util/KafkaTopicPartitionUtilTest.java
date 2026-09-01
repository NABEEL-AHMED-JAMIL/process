package process.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTopicPartitionUtilTest {

    @Test
    void parsesAWildcardPartition() {
        Optional<KafkaTopicPartitionUtil.Parsed> result = KafkaTopicPartitionUtil.parse("topic=my-topic&partitions=[*]");
        assertThat(result).isPresent();
        assertThat(result.get().getTopic()).isEqualTo("my-topic");
        assertThat(result.get().isWildcardPartition()).isTrue();
        assertThat(result.get().exceedsMaxPartitionIndex()).isFalse();
        assertThat(result.get().minimumPartitionCount()).isEqualTo(1);
    }

    @Test
    void parsesASpecificPartitionIndex() {
        Optional<KafkaTopicPartitionUtil.Parsed> result = KafkaTopicPartitionUtil.parse("topic=my-topic&partitions=[3]");
        assertThat(result).isPresent();
        assertThat(result.get().isWildcardPartition()).isFalse();
        assertThat(result.get().exceedsMaxPartitionIndex()).isFalse();
        assertThat(result.get().minimumPartitionCount()).isEqualTo(4);
    }

    @Test
    void indexExactlyAtTheMaxIsAllowed() {
        Optional<KafkaTopicPartitionUtil.Parsed> result = KafkaTopicPartitionUtil.parse(
            "topic=my-topic&partitions=[" + KafkaTopicPartitionUtil.MAX_PARTITION_INDEX + "]");
        assertThat(result).isPresent();
        assertThat(result.get().exceedsMaxPartitionIndex()).isFalse();
    }

    @Test
    void indexOneBeyondTheMaxIsRejected() {
        Optional<KafkaTopicPartitionUtil.Parsed> result = KafkaTopicPartitionUtil.parse(
            "topic=my-topic&partitions=[" + (KafkaTopicPartitionUtil.MAX_PARTITION_INDEX + 1) + "]");
        assertThat(result).isPresent();
        assertThat(result.get().exceedsMaxPartitionIndex()).isTrue();
    }

    @Test
    void aLargeOutOfRangeIndexIsRejected() {
        Optional<KafkaTopicPartitionUtil.Parsed> result = KafkaTopicPartitionUtil.parse("topic=my-topic&partitions=[999]");
        assertThat(result).isPresent();
        assertThat(result.get().exceedsMaxPartitionIndex()).isTrue();
    }

    /**
     * The names brokers are actually given. A pattern of letters and hyphens alone refused all
     * three, and a task type carrying one dispatched nothing at all.
     */
    @ParameterizedTest
    @ValueSource(strings = { "orders-v2", "etl.jobs.inbound", "job_events", "TOPIC9" })
    void acceptsEveryCharacterKafkaItselfAllowsInATopicName(String topic) {
        Optional<KafkaTopicPartitionUtil.Parsed> result =
            KafkaTopicPartitionUtil.parse("topic=" + topic + "&partitions=[*]");
        assertThat(result).isPresent();
        assertThat(result.get().getTopic()).isEqualTo(topic);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "topic=my-topic",
        "topic=my-topic&partitions=[]",
        "topic=my-topic&partitions=abc",
        "partitions=[1]",
        "topic=my-topic&partitions=[-1]",
        // No topic at all used to pass, and then failed on the broker once per run instead.
        "topic=&partitions=[0]",
        "topic=has space&partitions=[0]",
        "topic=slash/es&partitions=[0]"
    })
    void rejectsMalformedInput(String input) {
        assertThat(KafkaTopicPartitionUtil.parse(input)).isEmpty();
    }

    @Test
    void rejectsNullInput() {
        assertThat(KafkaTopicPartitionUtil.parse(null)).isEmpty();
    }
}
