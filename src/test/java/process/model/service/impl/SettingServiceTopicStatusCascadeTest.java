package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.dto.SourceTaskTypeDto;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.model.pojo.SourceTaskType;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.security.TenantContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What editing a topic does to the jobs behind it, and what it must not.
 *
 * <b>The defect this pins.</b> The console's topic dialog always sends a status, and the update
 * used to push it onto every linked job whenever one was present -- through a native update
 * that wrote UPPER(status). Two things followed: editing a topic's description re-activated
 * every job somebody had switched off, and the jobs' status became "ACTIVE", which no list,
 * tile or lookup matched, so thirty-three CareBridge jobs vanished from the console in one
 * save. The jobs follow the topic's status only when that status changes, and in the enum's
 * own spelling. A body that says nothing about the Kafka profile keeps the binding there is.
 *
 * @author Nabeel Ahmed
 */
public class SettingServiceTopicStatusCascadeTest {

    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private final SourceTaskTypeRepository taskTypes = mock(SourceTaskTypeRepository.class);
    private final KafkaTemplateProvider kafka = mock(KafkaTemplateProvider.class);
    private final KafkaConnectionResolver resolver = mock(KafkaConnectionResolver.class);

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private SettingServiceImpl service() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "admin@platform.local");
        when(this.resolver.resolve(any(), anyLong())).thenReturn(Optional.of(new KafkaConnectionProfile()));
        return new SettingServiceImpl(this.jobs, this.taskTypes, null, null, null, this.kafka, this.resolver, null);
    }

    private SourceTaskType existing(Status status, Long kafkaProfileId) {
        SourceTaskType row = new SourceTaskType();
        row.setSourceTaskTypeId(11764L);
        row.setServiceName("Object pipelines (worker)");
        row.setQueueTopicPartition("topic=scrapping-topic&partitions=[*]");
        row.setStatus(status);
        row.setKafkaConnectionProfileId(kafkaProfileId);
        row.setTenantId(2905L);
        when(this.taskTypes.findById(11764L)).thenReturn(Optional.of(row));
        return row;
    }

    private SourceTaskTypeDto edit(Status status, Long kafkaProfileId) {
        SourceTaskTypeDto dto = new SourceTaskTypeDto();
        dto.setSourceTaskTypeId(11764L);
        dto.setServiceName("Object pipelines (worker), renamed");
        dto.setDescription("The worker's topic.");
        dto.setQueueTopicPartition("topic=scrapping-topic&partitions=[*]");
        dto.setStatus(status);
        dto.setKafkaConnectionProfileId(kafkaProfileId);
        return dto;
    }

    @Test
    void anEditThatKeepsTheStatusLeavesTheJobsAlone() throws Exception {
        SourceTaskType row = this.existing(Status.Active, 1271L);
        this.service().updateSourceTaskType(this.edit(Status.Active, 1271L));
        verify(this.jobs, never()).statusChangeSourceJobLinkWithSourceTaskTypeId(anyLong(), anyString());
        assertThat(row.getServiceName()).isEqualTo("Object pipelines (worker), renamed");
        verify(this.kafka).ensureTopicExists(any(), eq("scrapping-topic"), anyInt());
    }

    /** MIG-45: no connection resolves for the topic -- it is made nowhere, not on the application's own brokers; the save stands. */
    @Test
    void aTopicThatResolvesToNoConnectionIsNotCreatedAnywhere() throws Exception {
        this.existing(Status.Active, 1271L);
        SettingServiceImpl service = this.service();
        when(this.resolver.resolve(any(), anyLong())).thenReturn(Optional.empty());

        assertThat(service.updateSourceTaskType(this.edit(Status.Active, 1271L)).getStatus()).isEqualTo("SUCCESS");

        verify(this.kafka, never()).ensureTopicExists(any(), any(), anyInt());
    }

    @Test
    void aStatusChangeCascadesInTheEnumsOwnSpelling() throws Exception {
        SourceTaskType row = this.existing(Status.Active, 1271L);
        this.service().updateSourceTaskType(this.edit(Status.Inactive, 1271L));
        verify(this.jobs).statusChangeSourceJobLinkWithSourceTaskTypeId(11764L, "Inactive");
        assertThat(row.getStatus()).isEqualTo(Status.Inactive);
    }

    @Test
    void aBodyWithNoKafkaProfileKeepsTheBinding() throws Exception {
        SourceTaskType row = this.existing(Status.Active, 1271L);
        this.service().updateSourceTaskType(this.edit(Status.Active, null));
        assertThat(row.getKafkaConnectionProfileId()).isEqualTo(1271L);
    }
}
