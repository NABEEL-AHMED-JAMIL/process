package process.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.SslAuthenticationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.model.pojo.KafkaConnectionProfile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Kafka &amp; Topics' live health of one profile's brokers (scale/UI review U2): the brokers up, each of the caller's
 * topics (partitions, replicas, under-replicated and offline partitions, messages kept), and the consumer groups that
 * read them with their lag. Only the topics the caller names -- the ones its workspace publishes through the profile --
 * and only the groups reading those: a shared platform broker says nothing of anyone else's topics.
 *
 * Bounded for a big cluster: at most {@link #MAX_TOPICS} topics and {@link #MAX_GROUPS} groups are looked at (the answer
 * says when it stopped short), everything shares one deadline, and an answer is kept {@link #FRESH_MS} ms per profile
 * and topic set, so every open tab polling once a minute costs the broker one look.
 */
@Component
public class KafkaHealth {

    static final int MAX_TOPICS = 500;
    static final int MAX_GROUPS = 300;
    static final long DEADLINE_MS = 8_000;
    static final long FRESH_MS = 20_000;

    private final Logger logger = LoggerFactory.getLogger(KafkaHealth.class);
    private final Function<KafkaConnectionProfile, AdminClient> admins;
    private final Clock clock;
    private final Map<String, Kept> kept = new ConcurrentHashMap<>();

    private static final class Kept {
        final long at;
        final Map<String, Object> report;

        Kept(long at, Map<String, Object> report) {
            this.at = at;
            this.report = report;
        }
    }

    @Autowired
    public KafkaHealth(KafkaTemplateProvider provider) {
        this(provider::adminClientFor, Clock.systemUTC());
    }

    KafkaHealth(Function<KafkaConnectionProfile, AdminClient> admins, Clock clock) {
        this.admins = admins;
        this.clock = clock;
    }

    /** The report for these topics on this profile; a fresh one when {@code recheck}, else one at most 20 s old. */
    public Map<String, Object> report(KafkaConnectionProfile profile, Collection<String> topicNames, boolean recheck) {
        Set<String> topics = new TreeSet<>();
        for (String t : topicNames) {
            if (t != null && !t.trim().isEmpty()) {
                topics.add(t.trim());
            }
        }
        String key = profile.getKafkaConnectionProfileId() + "|" + String.join(",", topics);
        long now = this.clock.millis();
        Kept hit = this.kept.get(key);
        if (!recheck && hit != null && now - hit.at < FRESH_MS) {
            return hit.report;
        }
        Map<String, Object> report = this.measure(profile, topics);
        if (this.kept.size() > 1_000) {
            this.kept.clear();
        }
        this.kept.put(key, new Kept(now, report));
        return report;
    }

    private Map<String, Object> measure(KafkaConnectionProfile profile, Set<String> allTopics) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checkedAt", Instant.ofEpochMilli(this.clock.millis()).toString());
        List<String> topics = new ArrayList<>(allTopics);
        boolean topicsCut = topics.size() > MAX_TOPICS;
        if (topicsCut) {
            topics = topics.subList(0, MAX_TOPICS);
        }
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        AdminClient admin;
        try {
            admin = this.admins.apply(profile);
        } catch (RuntimeException unmade) {
            return unreachable(report, unmade);
        }
        if (admin == null) {
            return unreachable(report, new IllegalStateException("no client"));
        }
        try {
            DescribeClusterResult cluster = admin.describeCluster();
            Collection<Node> nodes = await(cluster.nodes(), deadline);
            Node controller = await(cluster.controller(), deadline);
            report.put("reachable", true);
            report.put("brokers", nodes.size());
            report.put("controller", controller != null && !controller.isEmpty());

            // Topics: what exists, and the shape of each.
            Map<String, TopicDescription> described = new LinkedHashMap<>();
            Set<String> missing = new LinkedHashSet<>();
            if (!topics.isEmpty()) {
                @SuppressWarnings("deprecation")
                Map<String, KafkaFuture<TopicDescription>> futures = admin.describeTopics(topics).values();
                for (String t : topics) {
                    try {
                        described.put(t, await(futures.get(t), deadline));
                    } catch (ExecutionException failed) {
                        if (failed.getCause() instanceof UnknownTopicOrPartitionException) {
                            missing.add(t);
                        } else {
                            throw failed;
                        }
                    }
                }
            }
            List<TopicPartition> partitions = new ArrayList<>();
            for (TopicDescription d : described.values()) {
                for (TopicPartitionInfo p : d.partitions()) {
                    partitions.add(new TopicPartition(d.name(), p.partition()));
                }
            }
            Map<TopicPartition, Long> latest = this.offsets(admin, partitions, OffsetSpec.latest(), deadline);
            Map<TopicPartition, Long> earliest = this.offsets(admin, partitions, OffsetSpec.earliest(), deadline);

            // Groups: those with a member on one of these topics, or offsets committed on one.
            Set<String> ours = new LinkedHashSet<>(described.keySet());
            List<String> groupIds = new ArrayList<>();
            for (ConsumerGroupListing g : await(admin.listConsumerGroups().all(), deadline)) {
                groupIds.add(g.groupId());
            }
            Collections.sort(groupIds);
            boolean groupsCut = groupIds.size() > MAX_GROUPS;
            if (groupsCut) {
                groupIds = groupIds.subList(0, MAX_GROUPS);
            }
            Map<String, ConsumerGroupDescription> groups = new HashMap<>();
            if (!groupIds.isEmpty()) {
                for (Map.Entry<String, KafkaFuture<ConsumerGroupDescription>> e : admin.describeConsumerGroups(groupIds).describedGroups()
                    .entrySet()) {
                    try {
                        groups.put(e.getKey(), await(e.getValue(), deadline));
                    } catch (ExecutionException skipped) {
                        // A group the broker will not describe to this login: left out, not fatal.
                    }
                }
            }
            Map<String, KafkaFuture<Map<TopicPartition, OffsetAndMetadata>>> committedAsked = new LinkedHashMap<>();
            for (String g : groupIds) {
                committedAsked.put(g, admin.listConsumerGroupOffsets(g).partitionsToOffsetAndMetadata());
            }
            List<Map<String, Object>> groupRows = new ArrayList<>();
            Map<String, List<Map<String, Object>>> readersByTopic = new HashMap<>();
            for (String g : groupIds) {
                Map<TopicPartition, OffsetAndMetadata> committed;
                try {
                    committed = await(committedAsked.get(g), deadline);
                } catch (ExecutionException skipped) {
                    committed = Collections.emptyMap();
                }
                ConsumerGroupDescription d = groups.get(g);
                Set<String> assigned = new TreeSet<>();
                int members = 0;
                if (d != null) {
                    for (MemberDescription m : d.members()) {
                        boolean onOurs = false;
                        for (TopicPartition tp : m.assignment().topicPartitions()) {
                            if (ours.contains(tp.topic())) {
                                assigned.add(tp.topic());
                                onOurs = true;
                            }
                        }
                        if (onOurs) {
                            members++;
                        }
                    }
                }
                Set<String> read = new TreeSet<>(assigned);
                Map<String, Long> lagByTopic = new HashMap<>();
                if (committed != null) {
                    for (Map.Entry<TopicPartition, OffsetAndMetadata> c : committed.entrySet()) {
                        String topic = c.getKey().topic();
                        if (!ours.contains(topic) || c.getValue() == null) {
                            continue;
                        }
                        read.add(topic);
                        Long end = latest.get(c.getKey());
                        if (end != null) {
                            lagByTopic.merge(topic, Math.max(0, end - c.getValue().offset()), Long::sum);
                        }
                    }
                }
                if (read.isEmpty()) {
                    continue;
                }
                String state = d == null ? "Unknown" : stateName(d.state().name());
                long lag = 0;
                for (Long l : lagByTopic.values()) {
                    lag += l;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("group", g);
                row.put("state", state);
                row.put("members", members);
                row.put("topics", new ArrayList<>(read));
                row.put("lag", lag);
                groupRows.add(row);
                for (String topic : read) {
                    Map<String, Object> reader = new LinkedHashMap<>();
                    reader.put("group", g);
                    reader.put("state", state);
                    reader.put("members", assigned.contains(topic) ? members : 0);
                    reader.put("lag", lagByTopic.getOrDefault(topic, 0L));
                    readersByTopic.computeIfAbsent(topic, k -> new ArrayList<>()).add(reader);
                }
            }

            List<Map<String, Object>> topicRows = new ArrayList<>();
            int underReplicated = 0;
            int offline = 0;
            long maxLag = 0;
            for (String t : topics) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("topic", t);
                TopicDescription d = described.get(t);
                row.put("exists", d != null);
                List<Map<String, Object>> readers = readersByTopic.getOrDefault(t, Collections.emptyList());
                row.put("consumers", readers);
                if (d != null) {
                    int under = 0;
                    int down = 0;
                    long kept = 0;
                    for (TopicPartitionInfo p : d.partitions()) {
                        if (p.isr().size() < p.replicas().size()) {
                            under++;
                        }
                        if (p.leader() == null || p.leader().isEmpty()) {
                            down++;
                        }
                        TopicPartition tp = new TopicPartition(t, p.partition());
                        kept += Math.max(0, latest.getOrDefault(tp, 0L) - earliest.getOrDefault(tp, 0L));
                    }
                    row.put("partitions", d.partitions().size());
                    row.put("replicas", d.partitions().isEmpty() ? 0 : d.partitions().get(0).replicas().size());
                    row.put("underReplicated", under);
                    row.put("offline", down);
                    row.put("messages", kept);
                    underReplicated += under;
                    offline += down;
                }
                long lag = 0;
                boolean live = false;
                for (Map<String, Object> r : readers) {
                    lag = Math.max(lag, (Long) r.get("lag"));
                    live |= ((Integer) r.get("members")) > 0;
                }
                row.put("lag", lag);
                row.put("readers", readers.size());
                row.put("state", d == null ? "Missing" : !live ? "No consumer" : ((Integer) row.get("offline")) > 0 ? "Offline"
                    : ((Integer) row.get("underReplicated")) > 0 ? "Under-replicated" : "Healthy");
                maxLag = Math.max(maxLag, lag);
                topicRows.add(row);
            }
            report.put("topics", topicRows);
            report.put("groups", groupRows);
            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("topics", topics.size());
            totals.put("missing", missing.size());
            totals.put("partitions", partitions.size());
            totals.put("underReplicated", underReplicated);
            totals.put("offline", offline);
            totals.put("groups", groupRows.size());
            totals.put("groupsStable", groupRows.stream().filter(g -> "Stable".equals(g.get("state"))).count());
            totals.put("maxLag", maxLag);
            totals.put("unread", topicRows.stream().filter(r -> "No consumer".equals(r.get("state"))).count());
            report.put("totals", totals);
            if (topicsCut || groupsCut) {
                report.put("partial", String.format("Looked at the first %d topics and %d consumer groups only.", MAX_TOPICS, MAX_GROUPS));
            }
            return report;
        } catch (Exception failed) {
            this.logger.warn("Kafka health of profile {} could not be read: {}", profile.getKafkaConnectionProfileId(), failed.getMessage());
            return unreachable(report, failed);
        } finally {
            try {
                admin.close(Duration.ofSeconds(2));
            } catch (RuntimeException ignored) {
                // closing is best effort
            }
        }
    }

    private Map<TopicPartition, Long> offsets(AdminClient admin, List<TopicPartition> partitions, OffsetSpec spec, long deadline)
        throws Exception {
        Map<TopicPartition, Long> out = new HashMap<>();
        if (partitions.isEmpty()) {
            return out;
        }
        Map<TopicPartition, OffsetSpec> asked = new HashMap<>();
        for (TopicPartition tp : partitions) {
            asked.put(tp, spec);
        }
        for (Map.Entry<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> e : await(admin.listOffsets(asked).all(), deadline).entrySet()) {
            out.put(e.getKey(), e.getValue().offset());
        }
        return out;
    }

    private static <T> T await(KafkaFuture<T> future, long deadline) throws Exception {
        long left = Math.max(1, deadline - System.currentTimeMillis());
        try {
            return future.get(left, TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            throw new TimeoutException("The broker took too long to answer.");
        }
    }

    /** STABLE -> Stable, PREPARING_REBALANCE -> Preparing rebalance. */
    static String stateName(String raw) {
        String lower = raw.toLowerCase().replace('_', ' ');
        return lower.isEmpty() ? "Unknown" : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Ours (java.util.concurrent) or the client's own (org.apache.kafka.common.errors), which share the simple name. */
    private static boolean timedOut(Throwable t) {
        return t != null && "TimeoutException".equals(t.getClass().getSimpleName());
    }

    /** No host, port or client text: the same few sentences the connection test gives. */
    private static Map<String, Object> unreachable(Map<String, Object> report, Exception why) {
        Throwable cause = why.getCause() != null ? why.getCause() : why;
        String reason;
        if (cause instanceof SaslAuthenticationException) {
            reason = "Authentication rejected: check the username, password and mechanism.";
        } else if (cause instanceof SslAuthenticationException) {
            reason = "TLS handshake failed: check the certificates and truststore.";
        } else if (timedOut(cause) || timedOut(why)) {
            reason = "The brokers did not answer in time.";
        } else {
            reason = "The brokers could not be reached. The detail is in the server log.";
        }
        report.put("reachable", false);
        report.put("reason", reason);
        return report;
    }
}
