package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.identity.TestIdentity;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.pojo.SourceTaskType;
import process.model.repository.PipelineRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantRepository;
import process.security.TenantContext;
import process.util.UserNameResolver;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.List;

/**
 * A pipeline publishes on one topic (V43). The topic is required, has to exist, and has to be
 * one the pipeline's own workspace can see; and the task screen asks for the pipelines of a
 * topic rather than every pipeline the caller owns.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class PipelineTopicTest {

    private static final long TENANT_A = 2901L;
    private static final long TENANT_B = 2905L;

    @Mock private PipelineRepository pipelineRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private UserNameResolver userNameResolver;
    @Mock private SourceTaskTypeRepository sourceTaskTypeRepository;

    private PipelineServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new PipelineServiceImpl(this.pipelineRepository, TestIdentity.over(null, this.tenantRepository), this.userNameResolver, this.sourceTaskTypeRepository);
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 10L, "a@example.com");
        lenient().when(this.pipelineRepository.findAllByPipelineIdAndTenantIdAndStatusNot(any(), any(), any()))
            .thenReturn(Collections.emptyList());
        lenient().when(this.pipelineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private static SourceTaskType topic(long id, Long tenantId, Status status) {
        SourceTaskType t = new SourceTaskType();
        t.setSourceTaskTypeId(id);
        t.setTenantId(tenantId);
        t.setStatus(status);
        t.setServiceName("Claims intake");
        t.setQueueTopicPartition("topic=claims-intake&partitions=[*]");
        return t;
    }

    private static Pipeline pipeline(Long topicId) {
        Pipeline p = new Pipeline();
        p.setPipelineId("F768947");
        p.setPipelineName("Claims files");
        p.setSourceTaskTypeId(topicId);
        PipelineField f = new PipelineField();
        f.setTagKey("input_folder"); f.setLabel("Input folder");
        p.setFields(Arrays.asList(f));
        return p;
    }

    @Test
    void aPipelineNeedsATopic() {
        ResponseDto response = this.service.saveForm(pipeline(null));
        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("Choose the topic");
        verify(this.pipelineRepository, never()).save(any());
    }

    @Test
    void aDeletedOrUnknownTopicIsRefused() {
        when(this.sourceTaskTypeRepository.findById(77L)).thenReturn(Optional.of(topic(77L, TENANT_A, Status.Delete)));
        assertThat(this.service.saveForm(pipeline(77L)).getMessage()).contains("no longer exists");
        when(this.sourceTaskTypeRepository.findById(78L)).thenReturn(Optional.empty());
        assertThat(this.service.saveForm(pipeline(78L)).getMessage()).contains("no longer exists");
        verify(this.pipelineRepository, never()).save(any());
    }

    @Test
    void anotherWorkspacesTopicIsRefused() {
        when(this.sourceTaskTypeRepository.findById(79L)).thenReturn(Optional.of(topic(79L, TENANT_B, Status.Active)));
        ResponseDto response = this.service.saveForm(pipeline(79L));
        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("another workspace");
        verify(this.pipelineRepository, never()).save(any());
    }

    @Test
    void ownTopicIsSavedAndNamedOnTheWayOut() {
        when(this.sourceTaskTypeRepository.findById(80L)).thenReturn(Optional.of(topic(80L, TENANT_A, Status.Active)));
        when(this.sourceTaskTypeRepository.findAllById(any())).thenReturn(Collections.singletonList(topic(80L, TENANT_A, Status.Active)));

        ResponseDto response = this.service.saveForm(pipeline(80L));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        Pipeline saved = (Pipeline) response.getData();
        assertThat(saved.getSourceTaskTypeId()).isEqualTo(80L);
        assertThat(saved.getTenantId()).isEqualTo(TENANT_A);

        when(this.pipelineRepository.findAllBySourceTaskTypeIdAndStatusNotOrderByPipelineNameAsc(80L, Status.Delete))
            .thenReturn(Collections.singletonList(saved));
        ResponseDto listed = this.service.listForTopic(80L);
        Pipeline row = ((List<Pipeline>) listed.getData()).get(0);
        assertThat(row.getTopicName()).isEqualTo("Claims intake");
        assertThat(row.getKafkaTopic()).isEqualTo("claims-intake");
    }

    /** listForTopic is scoped: a tenant is never handed another tenant's pipeline on the same platform topic. */
    @Test
    void listingATopicShowsOnlyTheCallersOwnPipelines() {
        Pipeline mine = pipeline(80L); mine.setTenantId(TENANT_A);
        Pipeline theirs = pipeline(80L); theirs.setTenantId(TENANT_B);
        when(this.pipelineRepository.findAllBySourceTaskTypeIdAndStatusNotOrderByPipelineNameAsc(80L, Status.Delete))
            .thenReturn(Arrays.asList(mine, theirs));
        when(this.sourceTaskTypeRepository.findAllById(any())).thenReturn(Collections.emptyList());

        @SuppressWarnings("unchecked")
        List<Pipeline> rows = (List<Pipeline>) this.service.listForTopic(80L).getData();
        assertThat(rows).containsExactly(mine);
    }
}
