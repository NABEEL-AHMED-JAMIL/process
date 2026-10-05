package process.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListConsumerGroupsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.MemberAssignment;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import process.model.pojo.KafkaConnectionProfile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Kafka &amp; Topics' live health (UI review U2): one look at a profile's brokers reports the caller's topics, the groups
 * reading them and their lag -- and nothing of any other topic on a shared broker.
 */
class KafkaHealthTest {

    private static final Node BROKER = new Node(1, "kafka", 9092);
    private static final TopicPartition ORDERS_0 = new TopicPartition("orders", 0);
    private static final TopicPartition SECRET_0 = new TopicPartition("secret-topic", 0);

    private static <T> KafkaFuture<T> done(T value) {
        KafkaFutureImpl<T> f = new KafkaFutureImpl<>();
        f.complete(value);
        return f;
    }

    private static <T> KafkaFuture<T> failed(Throwable cause) {
        KafkaFutureImpl<T> f = new KafkaFutureImpl<>();
        f.completeExceptionally(cause);
        return f;
    }

    private static KafkaConnectionProfile profile() {
        KafkaConnectionProfile p = new KafkaConnectionProfile();
        p.setKafkaConnectionProfileId(7L);
        return p;
    }

    @SuppressWarnings("deprecation")
    private static AdminClient broker() {
        AdminClient admin = mock(AdminClient.class);
        DescribeClusterResult cluster = mock(DescribeClusterResult.class);
        when(cluster.nodes()).thenReturn(done(Collections.singletonList(BROKER)));
        when(cluster.controller()).thenReturn(done(BROKER));
        when(admin.describeCluster()).thenReturn(cluster);

        DescribeTopicsResult topics = mock(DescribeTopicsResult.class);
        Map<String, KafkaFuture<TopicDescription>> described = new HashMap<>();
        // Two replicas, one in sync: under-replicated.
        described.put("orders", done(new TopicDescription("orders", false, Collections.singletonList(
            new TopicPartitionInfo(0, BROKER, Arrays.asList(BROKER, new Node(2, "kafka-2", 9092)), Collections.singletonList(BROKER))))));
        described.put("missing", failed(new UnknownTopicOrPartitionException("no such topic")));
        when(topics.values()).thenReturn(described);
        when(admin.describeTopics(anyCollection())).thenReturn(topics);

        when(admin.listOffsets(anyMap())).thenAnswer(call -> {
            Map<TopicPartition, OffsetSpec> asked = call.getArgument(0);
            boolean latest = asked.values().iterator().next() instanceof OffsetSpec.LatestSpec;
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> out = new HashMap<>();
            for (TopicPartition tp : asked.keySet()) {
                out.put(tp, new ListOffsetsResult.ListOffsetsResultInfo(latest ? 100 : 10, 0, Optional.empty()));
            }
            ListOffsetsResult result = mock(ListOffsetsResult.class);
            when(result.all()).thenReturn(done(out));
            return result;
        });

        ListConsumerGroupsResult listed = mock(ListConsumerGroupsResult.class);
        when(listed.all()).thenReturn(done(Arrays.asList(new ConsumerGroupListing("worker-a", false),
            new ConsumerGroupListing("other-tenant", false), new ConsumerGroupListing("idle", false))));
        when(admin.listConsumerGroups()).thenReturn(listed);

        DescribeConsumerGroupsResult groups = mock(DescribeConsumerGroupsResult.class);
        Map<String, KafkaFuture<ConsumerGroupDescription>> byGroup = new HashMap<>();
        byGroup.put("worker-a", done(new ConsumerGroupDescription("worker-a", false, Collections.singletonList(new MemberDescription("m1",
            "c1", "h", new MemberAssignment(new HashSet<>(Collections.singletonList(ORDERS_0))))), "range", ConsumerGroupState.STABLE, BROKER)));
        byGroup.put("other-tenant", done(new ConsumerGroupDescription("other-tenant", false, Collections.singletonList(new MemberDescription(
            "m2", "c2", "h", new MemberAssignment(new HashSet<>(Collections.singletonList(SECRET_0))))), "range", ConsumerGroupState.STABLE,
            BROKER)));
        byGroup.put("idle", done(new ConsumerGroupDescription("idle", false, Collections.<MemberDescription>emptyList(), "", ConsumerGroupState.EMPTY,
            BROKER)));
        when(groups.describedGroups()).thenReturn(byGroup);
        when(admin.describeConsumerGroups(anyCollection())).thenReturn(groups);

        Map<String, Map<TopicPartition, OffsetAndMetadata>> committed = new HashMap<>();
        committed.put("worker-a", Collections.singletonMap(ORDERS_0, new OffsetAndMetadata(90)));
        committed.put("other-tenant", Collections.singletonMap(SECRET_0, new OffsetAndMetadata(5)));
        committed.put("idle", Collections.singletonMap(ORDERS_0, new OffsetAndMetadata(40)));
        when(admin.listConsumerGroupOffsets(anyString())).thenAnswer(call -> {
            ListConsumerGroupOffsetsResult result = mock(ListConsumerGroupOffsetsResult.class);
            when(result.partitionsToOffsetAndMetadata()).thenReturn(done(committed.get((String) call.getArgument(0))));
            return result;
        });
        return admin;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> topic(Map<String, Object> report, String name) {
        for (Map<String, Object> row : (List<Map<String, Object>>) report.get("topics")) {
            if (name.equals(row.get("topic"))) {
                return row;
            }
        }
        throw new AssertionError("no row for " + name);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportsTheCallersTopicsTheirReadersAndLagAndNothingElseOnTheBroker() {
        KafkaHealth health = new KafkaHealth(p -> broker(), Clock.systemUTC());

        Map<String, Object> report = health.report(profile(), Arrays.asList("orders", "missing", " "), false);

        assertThat(report.get("reachable")).isEqualTo(true);
        assertThat(report.get("brokers")).isEqualTo(1);
        assertThat(report.get("controller")).isEqualTo(true);
        Map<String, Object> orders = topic(report, "orders");
        assertThat(orders.get("exists")).isEqualTo(true);
        assertThat(orders.get("partitions")).isEqualTo(1);
        assertThat(orders.get("replicas")).isEqualTo(2);
        assertThat(orders.get("underReplicated")).isEqualTo(1);
        assertThat(orders.get("messages")).isEqualTo(90L);
        assertThat(orders.get("lag")).as("the worst reader: idle committed 40 of 100").isEqualTo(60L);
        assertThat(orders.get("state")).isEqualTo("Under-replicated");
        List<Map<String, Object>> readers = (List<Map<String, Object>>) orders.get("consumers");
        assertThat(readers).extracting(r -> r.get("group")).containsExactlyInAnyOrder("worker-a", "idle");
        assertThat(readers.stream().filter(r -> "worker-a".equals(r.get("group"))).findFirst().get())
            .containsEntry("lag", 10L).containsEntry("members", 1).containsEntry("state", "Stable");
        assertThat(topic(report, "missing")).containsEntry("exists", false).containsEntry("state", "Missing");
        assertThat((List<Map<String, Object>>) report.get("groups")).extracting(g -> g.get("group"))
            .containsExactlyInAnyOrder("worker-a", "idle");
        assertThat(report.toString()).as("another workspace's topic and group on the same broker").doesNotContain("secret-topic")
            .doesNotContain("other-tenant");
        Map<String, Object> totals = (Map<String, Object>) report.get("totals");
        assertThat(totals).containsEntry("topics", 2).containsEntry("missing", 1).containsEntry("underReplicated", 1)
            .containsEntry("groups", 2).containsEntry("groupsStable", 1L).containsEntry("maxLag", 60L);
    }

    @Test
    void aSecondLookWithinTwentySecondsIsTheFirstAnswerUnlessRechecked() {
        AtomicInteger made = new AtomicInteger();
        Instant[] now = {Instant.parse("2026-10-05T12:00:00Z")};
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        KafkaHealth health = new KafkaHealth(p -> {
            made.incrementAndGet();
            return broker();
        }, clock);

        health.report(profile(), Collections.singletonList("orders"), false);
        health.report(profile(), Collections.singletonList("orders"), false);
        assertThat(made.get()).isEqualTo(1);
        health.report(profile(), Collections.singletonList("orders"), true);
        assertThat(made.get()).isEqualTo(2);
        now[0] = now[0].plusSeconds(21);
        health.report(profile(), Collections.singletonList("orders"), false);
        assertThat(made.get()).isEqualTo(3);
    }

    @Test
    void anUnreachableBrokerIsOneSentenceWithNoHostOrClientText() {
        AdminClient admin = mock(AdminClient.class);
        DescribeClusterResult cluster = mock(DescribeClusterResult.class);
        when(cluster.nodes()).thenReturn(failed(new TimeoutException("Timed out waiting for a node assignment. kafka-internal:9092")));
        when(cluster.controller()).thenReturn(failed(new TimeoutException("x")));
        when(admin.describeCluster()).thenReturn(cluster);

        Map<String, Object> report = new KafkaHealth(p -> admin, Clock.systemUTC()).report(profile(), Collections.singletonList("orders"),
            true);

        assertThat(report).containsEntry("reachable", false).containsEntry("reason", "The brokers did not answer in time.");
        assertThat(report.toString()).doesNotContain("kafka-internal");
    }

    @Test
    void anAdminClientThatCannotBeMadeIsUnreachable() {
        Map<String, Object> report = new KafkaHealth(p -> {
            throw new IllegalStateException("bad bootstrap kafka-internal:9092");
        }, Clock.systemUTC()).report(profile(), Collections.singletonList("orders"), true);

        assertThat(report).containsEntry("reachable", false);
        assertThat(report.toString()).doesNotContain("kafka-internal");
    }

    @Test
    void groupStatesReadAsWords() {
        assertThat(KafkaHealth.stateName("PREPARING_REBALANCE")).isEqualTo("Preparing rebalance");
        assertThat(KafkaHealth.stateName("STABLE")).isEqualTo("Stable");
    }
}
