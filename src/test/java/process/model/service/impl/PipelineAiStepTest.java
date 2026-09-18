package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.AiPrompt;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.pojo.SourceTaskType;
import process.model.repository.AiPromptRepository;
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
import static org.mockito.Mockito.*;

/**
 * An AI step is checked at save so a run never discovers the problem: the prompt is this
 * workspace's and active, every required variable reads a field that comes before the step.
 */
@ExtendWith(MockitoExtension.class)
public class PipelineAiStepTest {

    private static final long TENANT_A = 2901L, TENANT_B = 2905L;

    @Mock private PipelineRepository pipelineRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private UserNameResolver userNameResolver;
    @Mock private SourceTaskTypeRepository sourceTaskTypeRepository;
    @Mock private AiPromptRepository aiPromptRepository;

    private PipelineServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new PipelineServiceImpl(this.pipelineRepository, this.tenantRepository, this.userNameResolver, this.sourceTaskTypeRepository);
        ReflectionTestUtils.setField(this.service, "aiPromptRepository", this.aiPromptRepository);
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 10L, "a@example.com");
        SourceTaskType topic = new SourceTaskType();
        topic.setSourceTaskTypeId(77L); topic.setTenantId(TENANT_A); topic.setStatus(Status.Active); topic.setServiceName("Claims intake");
        topic.setQueueTopicPartition("topic=claims&partitions=[*]");
        lenient().when(this.sourceTaskTypeRepository.findById(77L)).thenReturn(Optional.of(topic));
        lenient().when(this.pipelineRepository.findAllByPipelineIdAndTenantIdAndStatusNot(any(), any(), any())).thenReturn(Collections.emptyList());
        lenient().when(this.pipelineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private static AiPrompt prompt(Long tenantId, Status status) {
        AiPrompt p = new AiPrompt();
        p.setPromptId(1000L); p.setTenantId(tenantId); p.setStatus(status); p.setName("Summarise"); p.setVersion(1);
        p.setUserTemplate("{{claim_id}} {{document_text}}");
        p.setVariables("[{\"name\":\"claim_id\",\"required\":true},{\"name\":\"document_text\",\"required\":true},{\"name\":\"note\",\"required\":false}]");
        return p;
    }

    private static PipelineField field(String tag, String type) {
        PipelineField f = new PipelineField(); f.setTagKey(tag); f.setLabel(tag); f.setFieldType(type); return f;
    }

    private static Pipeline pipelineWith(String variableMap, PipelineField... after) {
        Pipeline p = new Pipeline();
        p.setPipelineId("F1"); p.setPipelineName("Claims"); p.setSourceTaskTypeId(77L);
        PipelineField step = field("summary", "ai"); step.setPromptId(1000L); step.setVariableMap(variableMap); step.setOnError("continue");
        java.util.List<PipelineField> fields = new java.util.ArrayList<>(Arrays.asList(field("claim_id", "text"), field("document", "textarea"), step));
        fields.addAll(Arrays.asList(after));
        p.setFields(fields);
        return p;
    }

    @Test
    void aWellMappedStepSavesWithItsSettings() {
        when(this.aiPromptRepository.findById(1000L)).thenReturn(Optional.of(prompt(TENANT_A, Status.Active)));
        ResponseDto response = this.service.saveForm(pipelineWith("{\"claim_id\":\"claim_id\",\"document_text\":\"document\"}"));
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        Pipeline saved = (Pipeline) response.getData();
        PipelineField step = saved.getFields().get(2);
        assertThat(step.getFieldType()).isEqualTo("ai");
        assertThat(step.getPromptId()).isEqualTo(1000L);
        assertThat(step.getOnError()).isEqualTo("continue");
        assertThat(step.isRequired()).isFalse();
    }

    @Test
    void aRequiredVariableWithNoFieldIsRefused() {
        when(this.aiPromptRepository.findById(1000L)).thenReturn(Optional.of(prompt(TENANT_A, Status.Active)));
        ResponseDto response = this.service.saveForm(pipelineWith("{\"claim_id\":\"claim_id\"}"));
        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("{{document_text}}");
        verify(this.pipelineRepository, never()).save(any());
    }

    @Test
    void aVariableReadingALaterFieldIsRefused() {
        when(this.aiPromptRepository.findById(1000L)).thenReturn(Optional.of(prompt(TENANT_A, Status.Active)));
        ResponseDto response = this.service.saveForm(pipelineWith("{\"claim_id\":\"claim_id\",\"document_text\":\"later\"}", field("later", "text")));
        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("<later>").contains("before it");
    }

    @Test
    void anotherWorkspacesOrInactivePromptIsRefused() {
        when(this.aiPromptRepository.findById(1000L)).thenReturn(Optional.of(prompt(TENANT_B, Status.Active)));
        assertThat(this.service.saveForm(pipelineWith("{\"claim_id\":\"claim_id\",\"document_text\":\"document\"}")).getMessage()).contains("cannot use");
        when(this.aiPromptRepository.findById(1000L)).thenReturn(Optional.of(prompt(TENANT_A, Status.Inactive)));
        assertThat(this.service.saveForm(pipelineWith("{\"claim_id\":\"claim_id\",\"document_text\":\"document\"}")).getMessage()).contains("not active");
        verify(this.pipelineRepository, never()).save(any());
    }
}
