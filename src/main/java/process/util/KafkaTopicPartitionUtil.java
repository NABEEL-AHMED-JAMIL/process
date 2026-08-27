package process.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Nabeel Ahmed
 * */
public class KafkaTopicPartitionUtil {

    private static final Pattern PATTERN = Pattern.compile("^topic=([a-zA-Z-]*)&partitions=\\[([0-9]+|\\*)\\]$");

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
