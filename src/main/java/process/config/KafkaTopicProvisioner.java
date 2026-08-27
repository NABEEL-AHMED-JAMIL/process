package process.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import process.model.enums.Status;
import process.model.pojo.SourceTaskType;
import process.model.repository.SourceTaskTypeRepository;
import process.util.KafkaTopicPartitionUtil;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class KafkaTopicProvisioner implements ApplicationRunner {

    private final Logger logger = LoggerFactory.getLogger(KafkaTopicProvisioner.class);

    private final SourceTaskTypeRepository sourceTaskTypeRepository;
    private final KafkaTemplateProvider kafkaTemplateProvider;
    private final KafkaConnectionResolver kafkaConnectionResolver;

    public KafkaTopicProvisioner(SourceTaskTypeRepository sourceTaskTypeRepository,
        KafkaTemplateProvider kafkaTemplateProvider, KafkaConnectionResolver kafkaConnectionResolver) {
        this.sourceTaskTypeRepository = sourceTaskTypeRepository;
        this.kafkaTemplateProvider = kafkaTemplateProvider;
        this.kafkaConnectionResolver = kafkaConnectionResolver;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<SourceTaskType> activeSourceTaskTypes = this.sourceTaskTypeRepository.findByStatus(Status.Active);
            this.logger.info("KafkaTopicProvisioner -- provisioning topics for {} active Source TaskType(s).",
                activeSourceTaskTypes.size());
            activeSourceTaskTypes.forEach(sourceTaskType ->
                KafkaTopicPartitionUtil.parse(sourceTaskType.getQueueTopicPartition())
                    .ifPresent(parsed -> this.kafkaTemplateProvider.ensureTopicExists(
                        this.kafkaConnectionResolver.resolve(sourceTaskType.getTenantId(), sourceTaskType.getSourceTaskTypeId()),
                        parsed.getTopic(), parsed.minimumPartitionCount())));
        } catch (Exception ex) {

            this.logger.warn("KafkaTopicProvisioner -- startup provisioning failed: {}", ex.getMessage());
        }
    }

}
