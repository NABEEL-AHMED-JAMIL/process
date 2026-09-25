package process.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.model.pojo.SourceTaskType;
import process.model.repository.SourceTaskTypeRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.apache.kafka.common.errors.PolicyViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Assumptions;
import java.util.concurrent.Executor;
import org.mockito.ArgumentMatchers;
import java.util.LinkedHashMap;
import java.net.ServerSocket;
import org.mockito.Mockito;
import java.time.Duration;

/**
 * Startup topic provisioning (KafkaTopicProvisioner): process logged ~1,400 "Could not auto-create Kafka topic"
 * WARN lines at every start -- one per topic of every Kafka profile whose brokers do not resolve (the test
 * profiles on *.medaxiscare.demo hosts) -- and did it on the main thread, delaying readiness by ~2 minutes.
 *
 * Now: one AdminClient per profile, not per topic; an unreachable profile is ONE warning with the count of topics
 * skipped; the work runs off the startup thread; and for a broker that answers, the outcome is what it always
 * was -- exactly the missing topics created, with the task type's partition count and the replication factor,
 * an existing one left alone, a per-topic warning only for a topic that broker refused.
 */
class KafkaTopicProvisioningTest {

    private ListAppender<ILoggingEvent> log;
    private final List<Logger> watched = new ArrayList<>();

    @BeforeEach
    void watchLogs() {
        this.log = new ListAppender<>();
        this.log.start();
        for (Class<?> c : new Class<?>[] { KafkaTopicProvisioner.class, KafkaTemplateProvider.class }) {
            Logger logger = (Logger) LoggerFactory.getLogger(c);
            logger.addAppender(this.log);
            this.watched.add(logger);
        }
    }

    @AfterEach
    void unwatch() {
        this.watched.forEach(l -> l.detachAppender(this.log));
    }

    private List<String> warnings() {
        return this.log.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.toList());
    }

    private static KafkaConnectionProfile profile(long id, String name, String bootstrap) {
        KafkaConnectionProfile p = new KafkaConnectionProfile();
        p.setKafkaConnectionProfileId(id);
        p.setProfileName(name);
        p.setBootstrapServers(bootstrap);
        p.setSecurityProtocol("PLAINTEXT");
        return p;
    }

    private static SourceTaskType taskType(long id, String topic, String partitions) {
        SourceTaskType t = new SourceTaskType();
        t.setSourceTaskTypeId(id);
        t.setTenantId(2905L);
        t.setQueueTopicPartition("topic=" + topic + "&partitions=[" + partitions + "]");
        return t;
    }

    /** A provisioner over these task types, each on the profile the map gives it; runs where the executor says. */
    private static KafkaTopicProvisioner provisioner(KafkaTemplateProvider provider, List<SourceTaskType> types,
        Map<Long, Optional<KafkaConnectionProfile>> profileOf, Executor background) {
        SourceTaskTypeRepository repository = mock(SourceTaskTypeRepository.class);
        when(repository.findByStatus(Status.Active)).thenReturn(types);
        KafkaConnectionResolver resolver = mock(KafkaConnectionResolver.class);
        when(resolver.resolve(any(), any())).thenAnswer(inv -> profileOf.getOrDefault(inv.<Long>getArgument(1), Optional.empty()));
        return new KafkaTopicProvisioner(repository, provider, resolver, background);
    }

    @Test
    @Timeout(30)
    void anUnreachableProfileIsOneWarningWithItsCountAndNoLinePerTopic() {
        KafkaTemplateProvider provider = new KafkaTemplateProvider(null, null);
        KafkaConnectionProfile nowhere = profile(41L, "MCN test cluster", "broker-1.medaxiscare.demo:9092,broker-2.medaxiscare.demo:9092");
        List<SourceTaskType> types = new ArrayList<>();
        Map<Long, Optional<KafkaConnectionProfile>> profileOf = new HashMap<>();
        for (long i = 0; i < 40; i++) {
            types.add(taskType(i, "mcn-197-intake-" + i, "*"));
            profileOf.put(i, Optional.of(nowhere));
        }

        long started = System.nanoTime();
        provisioner(provider, types, profileOf, Runnable::run).run(null);

        assertThat(this.warnings()).noneMatch(w -> w.contains("Could not auto-create Kafka topic"));
        assertThat(this.warnings()).hasSize(1);
        assertThat(this.warnings().get(0)).contains("41").contains("MCN test cluster").contains("unreachable")
            .contains("40 topic(s)");
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).as("bounded").isLessThan(25);
    }

    @Test
    @Timeout(30)
    void startupIsNotHeldWhileTopicsAreProvisioned() throws Exception {
        KafkaTemplateProvider provider = mock(KafkaTemplateProvider.class);
        CountDownLatch brokerAnswers = new CountDownLatch(1);
        CountDownLatch provisioned = new CountDownLatch(1);
        when(provider.ensureTopicsExist(any(), any())).thenAnswer(inv -> {
            brokerAnswers.await(20, TimeUnit.SECONDS);
            provisioned.countDown();
            return KafkaTemplateProvider.TopicProvisioning.reached(0, 1);
        });
        Map<Long, Optional<KafkaConnectionProfile>> profileOf = new HashMap<>();
        profileOf.put(1L, Optional.of(profile(1L, "slow", "slow:9092")));
        KafkaTopicProvisioner provisioner = provisioner(provider, Arrays.asList(taskType(1L, "slow", "0")), profileOf, null);

        long started = System.nanoTime();
        provisioner.run(null);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("run() returns at once").isLessThan(2000);

        brokerAnswers.countDown();
        assertThat(provisioned.await(10, TimeUnit.SECONDS)).as("the work still happens, in the background").isTrue();
    }

    @Test
    void aProfileIsAskedOnceForAllItsTopicsAndEachProfileSeparately() {
        KafkaTemplateProvider provider = mock(KafkaTemplateProvider.class);
        when(provider.ensureTopicsExist(any(), any())).thenReturn(KafkaTemplateProvider.TopicProvisioning.reached(0, 0));
        KafkaConnectionProfile a = profile(1L, "a", "a:9092");
        KafkaConnectionProfile b = profile(2L, "b", "b:9092");
        Map<Long, Optional<KafkaConnectionProfile>> profileOf = new HashMap<>();
        profileOf.put(10L, Optional.of(a));
        profileOf.put(11L, Optional.of(a));
        profileOf.put(12L, Optional.of(b));
        List<SourceTaskType> types = Arrays.asList(taskType(10L, "a1", "*"), taskType(11L, "a2", "3"), taskType(12L, "b1", "0"),
            taskType(13L, "d1", "1"), taskType(14L, "a1", "7"));
        profileOf.put(14L, Optional.of(a));

        provisioner(provider, types, profileOf, Runnable::run).run(null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Integer>> topics = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<KafkaConnectionProfile> profiles = ArgumentCaptor.forClass(KafkaConnectionProfile.class);
        verify(provider, Mockito.times(2)).ensureTopicsExist(profiles.capture(), topics.capture());
        Map<String, Map<String, Integer>> byProfile = new HashMap<>();
        for (int i = 0; i < 2; i++) {
            byProfile.put(profiles.getAllValues().get(i).getProfileName(), topics.getAllValues().get(i));
        }
        // The first task type naming a topic decides its partitions, as the per-topic loop did (the first created it).
        assertThat(byProfile.get("a")).containsOnly(Map.entry("a1", 1), Map.entry("a2", 4));
        assertThat(byProfile.get("b")).containsOnly(Map.entry("b1", 1));
        verify(provider, never()).ensureTopicExists(any(), any(), ArgumentMatchers.anyInt());
    }

    /**
     * MIG-45: a task type no connection resolves for is provisioned nowhere -- not on the application's own
     * brokers, where its runs will never be sent -- and one line says which, not one per topic.
     */
    @Test
    void aTaskTypeThatResolvesToNoConnectionIsNotProvisionedAnywhere() {
        KafkaTemplateProvider provider = mock(KafkaTemplateProvider.class);
        when(provider.ensureTopicsExist(any(), any())).thenReturn(KafkaTemplateProvider.TopicProvisioning.reached(0, 0));
        Map<Long, Optional<KafkaConnectionProfile>> profileOf = new HashMap<>();
        profileOf.put(10L, Optional.of(profile(1L, "a", "a:9092")));

        provisioner(provider, Arrays.asList(taskType(10L, "a1", "*"), taskType(13L, "d1", "1"), taskType(15L, "d2", "0")),
            profileOf, Runnable::run).run(null);

        verify(provider, Mockito.times(1)).ensureTopicsExist(any(), any());
        verify(provider, never()).ensureTopicsExist(ArgumentMatchers.isNull(), any());
        assertThat(this.warnings()).hasSize(1);
        assertThat(this.warnings().get(0)).contains("2 task type(s) resolve to no Kafka connection").contains("[13, 15]");
    }

    // ---- a broker that answers: the same outcome as the per-topic loop ------------------------------------------

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

    @Test
    void aReachableBrokerGetsExactlyTheTopicsItLacks() {
        AdminClient admin = mock(AdminClient.class);
        ListTopicsResult listed = mock(ListTopicsResult.class);
        when(listed.names()).thenReturn(done(new HashSet<>(Arrays.asList("already-there", "other"))));
        when(admin.listTopics()).thenReturn(listed);
        CreateTopicsResult created = mock(CreateTopicsResult.class);
        Map<String, KafkaFuture<Void>> outcomes = new HashMap<>();
        outcomes.put("new-one", done(null));
        outcomes.put("raced", failed(new TopicExistsException("made meanwhile")));
        outcomes.put("refused", failed(new PolicyViolationException("no")));
        when(created.values()).thenReturn(outcomes);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<NewTopic>> asked = ArgumentCaptor.forClass(Collection.class);
        when(admin.createTopics(asked.capture())).thenReturn(created);
        KafkaTemplateProvider provider = new KafkaTemplateProvider(null, null);
        List<Map<String, Object>> clientsMadeWith = new ArrayList<>();
        provider.useAdminClients(props -> {
            clientsMadeWith.add(props);
            return admin;
        });
        Map<String, Integer> topics = new LinkedHashMap<>();
        topics.put("already-there", 3);
        topics.put("new-one", 4);
        topics.put("raced", 1);
        topics.put("refused", 2);

        KafkaTemplateProvider.TopicProvisioning outcome = provider.ensureTopicsExist(profile(7L, "dev", "localhost:9092"), topics);

        assertThat(clientsMadeWith).as("one client for the profile").hasSize(1);
        assertThat(asked.getValue()).extracting(NewTopic::name).containsExactlyInAnyOrder("new-one", "raced", "refused");
        assertThat(asked.getValue()).filteredOn(t -> t.name().equals("new-one")).first()
            .satisfies(t -> assertThat(t.numPartitions()).isEqualTo(4));
        assertThat(outcome.isReached()).isTrue();
        assertThat(outcome.getCreated()).isEqualTo(1);
        assertThat(outcome.getExisting()).isEqualTo(2);
        assertThat(this.log.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
            .isEqualTo("Auto-created Kafka topic 'new-one' with 4 partition(s)."));
        assertThat(this.warnings()).containsExactly("Could not auto-create Kafka topic 'refused': "
            + "org.apache.kafka.common.errors.PolicyViolationException: no");
        verify(admin).close(any(Duration.class));
    }

    @Test
    void aProfileWhoseClientCannotBeBuiltIsUnreachableNotAThrow() {
        KafkaTemplateProvider provider = new KafkaTemplateProvider(null, null);
        provider.useAdminClients(props -> {
            throw new KafkaException("Failed to create new KafkaAdminClient");
        });
        Map<String, Integer> topics = new HashMap<>();
        topics.put("x", 1);
        topics.put("y", 1);

        KafkaTemplateProvider.TopicProvisioning outcome = provider.ensureTopicsExist(profile(9L, "gone", "gone:9092"), topics);

        assertThat(outcome.isReached()).isFalse();
        assertThat(outcome.getReason()).contains("Failed to create new KafkaAdminClient");
        assertThat(this.warnings()).as("the caller says it once, for the profile").isEmpty();
    }

    /**
     * A broker that resolves but never answers (a closed port here) is unreachable within the step's bound -- listed
     * for PROVISIONING_STEP_SECONDS at most, closed in a second -- never the minute close() alone would wait.
     */
    @Test
    @Timeout(40)
    void aBrokerThatNeverAnswersIsUnreachableWithinTheBound() throws Exception {
        try (ServerSocket closed = new ServerSocket(0)) {
            int port = closed.getLocalPort();
            closed.close();
            KafkaTemplateProvider provider = new KafkaTemplateProvider(null, null);
            Map<String, Integer> topics = new HashMap<>();
            topics.put("t", 1);

            long started = System.nanoTime();
            KafkaTemplateProvider.TopicProvisioning outcome = provider.ensureTopicsExist(profile(3L, "silent", "127.0.0.1:" + port), topics);

            assertThat(outcome.isReached()).isFalse();
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started))
                .isLessThanOrEqualTo(KafkaTemplateProvider.PROVISIONING_STEP_SECONDS + 5);
        }
    }

    /** Opt-in against the dev broker (PROCESS_TEST_KAFKA=localhost:9092): the real client, a real create. */
    @Test
    void againstARealBrokerTheMissingTopicIsCreatedWithItsPartitions() throws Exception {
        String bootstrap = System.getenv("PROCESS_TEST_KAFKA");
        Assumptions.assumeTrue(bootstrap != null && !bootstrap.isEmpty(), "PROCESS_TEST_KAFKA not set");
        KafkaTemplateProvider provider = new KafkaTemplateProvider(null, null);
        // What kafka.topic.replication-factor gives the application; a provider built by hand has none.
        ReflectionTestUtils.setField(provider, "defaultReplicationFactor", (short) 1);
        String topic = "process-provisioning-test-" + System.nanoTime();
        Map<String, Integer> topics = new HashMap<>();
        topics.put(topic, 3);

        KafkaTemplateProvider.TopicProvisioning outcome = provider.ensureTopicsExist(profile(1L, "dev", bootstrap), topics);
        KafkaTemplateProvider.TopicProvisioning again = provider.ensureTopicsExist(profile(1L, "dev", bootstrap), topics);

        assertThat(outcome.getCreated()).isEqualTo(1);
        assertThat(again.getCreated()).isZero();
        assertThat(again.getExisting()).isEqualTo(1);
        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", bootstrap);
        try (AdminClient admin = AdminClient.create(props)) {
            assertThat(admin.describeTopics(Arrays.asList(topic)).all().get(10, TimeUnit.SECONDS).get(topic).partitions()).hasSize(3);
            admin.deleteTopics(Arrays.asList(topic)).all().get(10, TimeUnit.SECONDS);
        }
    }
}
