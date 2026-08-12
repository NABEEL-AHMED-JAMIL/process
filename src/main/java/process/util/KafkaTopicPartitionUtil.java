package process.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single source of truth for parsing a SourceTaskType's queueTopicPartition string
 * (e.g. "topic=scrapping-topic&partitions=[*]" or "topic=truck-topic&partitions=[2]").
 * Previously ProducerBulkEngine held its own private copy of this regex -- any Kafka topic
 * provisioning (KafkaTemplateProvider.ensureTopicExists, called from SettingServiceImpl on
 * SourceTaskType add/update and on startup) needs to agree with it exactly, so it's centralized
 * here instead of duplicated.
 * @author Nabeel Ahmed
 */
public class KafkaTopicPartitionUtil {

    /**
     * partition group accepts either a literal "*" (send to any partition) or one-or-more
     * digits (e.g. "10") naming a specific partition index to target.
     * */
    private static final Pattern PATTERN = Pattern.compile("^topic=([a-zA-Z-]*)&partitions=\\[([0-9]+|\\*)\\]$");

    /** Highest partition index a SourceTaskType may target -- keeps a single mis-typed/huge
     * value from provisioning a topic with an unreasonable partition count (see
     * Parsed.minimumPartitionCount(), which is derived directly from this index and is what
     * KafkaTemplateProvider.ensureTopicExists actually creates the topic with). Enforced by
     * SettingServiceImpl.addSourceTaskType/updateSourceTaskType via Parsed.exceedsMaxPartitionIndex()
     * below, and mirrored by the frontend's partition dropdown (0-10) so an out-of-range value
     * can't even be entered in the first place. */
    public static final int MAX_PARTITION_INDEX = 10;

    private KafkaTopicPartitionUtil() {}

    /**
     * Method use to parse a queueTopicPartition string into its topic + partition parts.
     * @param queueTopicPartition
     * @return Optional<Parsed> -- empty when the string is missing or doesn't match the expected format
     * */
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

    /**
     * Value holder for a parsed queueTopicPartition -- also works out the minimum partition
     * count the topic needs so the configured partition index is always addressable (a wildcard
     * "*" just needs the topic to exist, so it defaults to a single partition).
     * */
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

        /**
         * Method use to check whether this config's target partition index is beyond
         * MAX_PARTITION_INDEX -- always false for a wildcard ("*" doesn't name a specific index
         * to bound).
         * @return boolean
         * */
        public boolean exceedsMaxPartitionIndex() {
            return !this.isWildcardPartition() && Integer.parseInt(this.partition) > MAX_PARTITION_INDEX;
        }

        /**
         * Method use to get the minimum number of partitions the topic must have for this
         * config's target partition (a specific index) to be addressable.
         * @return int
         * */
        public int minimumPartitionCount() {
            if (this.isWildcardPartition()) {
                return 1;
            }
            return Integer.parseInt(this.partition) + 1;
        }
    }

}
