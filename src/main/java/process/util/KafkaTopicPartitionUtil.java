package process.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Nabeel Ahmed
 * */
public class KafkaTopicPartitionUtil {

    /**
     * The topic half accepts what a Kafka broker itself accepts -- letters, digits, dot,
     * underscore, hyphen, up to 249 characters. Letters and hyphens alone rejected the names
     * brokers are actually given ("orders-v2", "etl.jobs", "job_events"), and a task type
     * carrying one dispatched nothing: parse() returned empty and every run of that job failed
     * with "Broker configuration is invalid".
     *
     * At least one character, where it used to be any number: an empty topic passed validation
     * on save and then failed at send time, on the broker, once per run.
     */
    private static final Pattern PATTERN =
        Pattern.compile("^topic=([a-zA-Z0-9._-]{1,249})&partitions=\\[([0-9]+|\\*)\\]$");

    public static final int MAX_PARTITION_INDEX = 10;

    private KafkaTopicPartitionUtil() {}

    public static Optional<Parsed> parse(String queueTopicPartition) {
        if (queueTopicPartition == null || queueTopicPartition.trim().isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(queueTopicPartition.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(matcher.group(1), matcher.group(2)));
    }

    public static class Parsed {

        private final String topic;
        private final String partition;

        public Parsed(String topic, String partition) {
            this.topic = topic;
            this.partition = partition;
        }

        public String getTopic() {
            return topic;
        }

        public String getPartition() {
            return partition;
        }

        public boolean isWildcardPartition() {
            return ProcessUtil.START.equals(this.partition);
        }

        public boolean exceedsMaxPartitionIndex() {
            return !this.isWildcardPartition() && Integer.parseInt(this.partition) > MAX_PARTITION_INDEX;
        }

        public int minimumPartitionCount() {
            if (this.isWildcardPartition()) {
                return 1;
            }
            return Integer.parseInt(this.partition) + 1;
        }
    }

}
