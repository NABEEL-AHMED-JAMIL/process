package process.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.model.pojo.SourceTaskType;
import process.model.repository.SourceTaskTypeRepository;
import process.util.KafkaTopicPartitionUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * At startup, makes sure every active task type's topic exists on the brokers its Kafka profile names.
 *
 * Per PROFILE, not per topic: one AdminClient lists a profile's topics once and creates the missing ones in one
 * request (KafkaTemplateProvider.ensureTopicsExist). A profile whose brokers cannot be reached -- the test profiles
 * on hosts that do not resolve -- is one WARN line with the number of topics skipped, where it used to be one line
 * per topic (~1,400 a start). The profiles are provisioned a few at a time, each bounded by the broker timeouts,
 * and all of it OFF the startup thread: the application is ready while topics are still being made, and a run
 * dispatched meanwhile to a topic not yet made is published as before (the broker's own auto-create, or a refused
 * send the dispatch path already reports).
 *
 * @author Nabeel Ahmed
 */
@Component
public class KafkaTopicProvisioner implements ApplicationRunner {

    /** How many profiles are provisioned at once. */
    static final int PARALLEL_PROFILES = 4;

    private final Logger logger = LoggerFactory.getLogger(KafkaTopicProvisioner.class);

    private final SourceTaskTypeRepository sourceTaskTypeRepository;
    private final KafkaTemplateProvider kafkaTemplateProvider;
    private final KafkaConnectionResolver kafkaConnectionResolver;
    /** Where the provisioning runs; null means a daemon thread of its own, so startup never waits for it. */
    private final Executor background;

    @Autowired
    public KafkaTopicProvisioner(SourceTaskTypeRepository sourceTaskTypeRepository,
        KafkaTemplateProvider kafkaTemplateProvider, KafkaConnectionResolver kafkaConnectionResolver) {
        this(sourceTaskTypeRepository, kafkaTemplateProvider, kafkaConnectionResolver, null);
    }

    KafkaTopicProvisioner(SourceTaskTypeRepository sourceTaskTypeRepository, KafkaTemplateProvider kafkaTemplateProvider,
        KafkaConnectionResolver kafkaConnectionResolver, Executor background) {
        this.sourceTaskTypeRepository = sourceTaskTypeRepository;
        this.kafkaTemplateProvider = kafkaTemplateProvider;
        this.kafkaConnectionResolver = kafkaConnectionResolver;
        this.background = background;
    }

    @Override
    public void run(ApplicationArguments args) {
        Runnable work = () -> {
            try {
                this.provisionAll();
            } catch (Exception ex) {
                this.logger.warn("KafkaTopicProvisioner -- startup provisioning failed: {}", ex.getMessage());
            }
        };
        if (this.background != null) {
            this.background.execute(work);
            return;
        }
        Thread thread = new Thread(work, "kafka-topic-provisioner");
        thread.setDaemon(true);
        thread.start();
    }

    /** One profile's share: the profile (empty for the application's default brokers) and its topics, first come first. */
    private static final class ProfileTopics {
        private final Optional<KafkaConnectionProfile> profile;
        private final Map<String, Integer> topics = new LinkedHashMap<>();

        private ProfileTopics(Optional<KafkaConnectionProfile> profile) {
            this.profile = profile;
        }
    }

    void provisionAll() throws InterruptedException {
        List<SourceTaskType> activeSourceTaskTypes = this.sourceTaskTypeRepository.findByStatus(Status.Active);
        this.logger.info("KafkaTopicProvisioner -- provisioning topics for {} active Source TaskType(s), in the background.",
            activeSourceTaskTypes.size());
        // Keyed by profile id; the default brokers under null. The first task type naming a topic decides its
        // partitions -- as the per-topic loop did, where the first call created it and the rest found it there.
        Map<Long, ProfileTopics> byProfile = new LinkedHashMap<>();
        for (SourceTaskType sourceTaskType : activeSourceTaskTypes) {
            Optional<KafkaTopicPartitionUtil.Parsed> parsed = KafkaTopicPartitionUtil.parse(sourceTaskType.getQueueTopicPartition());
            if (!parsed.isPresent()) {
                continue;
            }
            Optional<KafkaConnectionProfile> profile;
            try {
                profile = this.kafkaConnectionResolver.resolve(sourceTaskType.getTenantId(), sourceTaskType.getSourceTaskTypeId());
            } catch (RuntimeException ex) {
                this.logger.warn("KafkaTopicProvisioner -- task type {}: its Kafka profile could not be resolved: {}",
                    sourceTaskType.getSourceTaskTypeId(), ex.getMessage());
                continue;
            }
            Long key = profile.map(KafkaConnectionProfile::getKafkaConnectionProfileId).orElse(null);
            byProfile.computeIfAbsent(key, k -> new ProfileTopics(profile)).topics
                .putIfAbsent(parsed.get().getTopic(), parsed.get().minimumPartitionCount());
        }
        AtomicInteger created = new AtomicInteger();
        AtomicInteger existing = new AtomicInteger();
        AtomicInteger unreachable = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_PROFILES, runnable -> {
            Thread thread = new Thread(runnable, "kafka-topic-provisioner-profile");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> done = new ArrayList<>();
            for (ProfileTopics share : byProfile.values()) {
                done.add(pool.submit(() -> {
                    KafkaTemplateProvider.TopicProvisioning outcome;
                    try {
                        outcome = this.kafkaTemplateProvider.ensureTopicsExist(share.profile, share.topics);
                    } catch (RuntimeException ex) {
                        outcome = KafkaTemplateProvider.TopicProvisioning.unreachable(ex.getMessage());
                    }
                    if (outcome.isReached()) {
                        created.addAndGet(outcome.getCreated());
                        existing.addAndGet(outcome.getExisting());
                        return;
                    }
                    unreachable.incrementAndGet();
                    skipped.addAndGet(share.topics.size());
                    this.logger.warn("KafkaTopicProvisioner -- {} is unreachable ({}); {} topic(s) not auto-created.",
                        describe(share.profile), outcome.getReason(), share.topics.size());
                }));
            }
            for (Future<?> future : done) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException ex) {
                    this.logger.warn("KafkaTopicProvisioner -- a profile's provisioning failed: {}", ex.getMessage());
                }
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(1, TimeUnit.MINUTES);
        }
        this.logger.info("KafkaTopicProvisioner -- done: {} profile(s), {} topic(s) created, {} already there, "
            + "{} unreachable profile(s) with {} topic(s) skipped.", byProfile.size(), created.get(), existing.get(),
            unreachable.get(), skipped.get());
    }

    private static String describe(Optional<KafkaConnectionProfile> profile) {
        return profile.map(p -> String.format("Kafka profile %s ('%s', %s)", p.getKafkaConnectionProfileId(), p.getProfileName(),
            p.getBootstrapServers())).orElse("the default Kafka brokers");
    }
}
