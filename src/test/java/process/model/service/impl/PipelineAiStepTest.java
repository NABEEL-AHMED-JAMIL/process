package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import process.identity.TestIdentity;
import process.ai.AiPort;
import process.model.dto.AiPromptDto;
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
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    @Mock private AiPort ai;

    private PipelineServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new PipelineServiceImpl(this.pipelineRepository, TestIdentity.over(null, this.tenantRepository), this.userNameResolver, this.sourceTaskTypeRepository);
        ReflectionTestUtils.setField(this.service, "ai", this.ai);
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 10L, "a@example.com");
        SourceTaskType topic = new SourceTaskType();
        topic.setSourceTaskTypeId(77L); topic.setTenantId(TENANT_A); topic.setStatus(Status.Active); topic.setServiceName("Claims intake");
        topic.setQueueTopicPartition("topic=claims&partitions=[*]");
        lenient().when(this.sourceTaskTypeRepository.findById(77L)).thenReturn(Optional.of(topic));
        lenient().when(this.pipelineRepository.findAllByPipelineIdAndTenantIdAndStatusNot(any(), any(), any())).thenReturn(Collections.emptyList());
        lenient().when(this.pipelineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clear() throws Exception { TenantContext.clear(); }

    private static Map<Long, AiPort.PromptInfo> prompt(Long tenantId, Status status) {
        AiPort.PromptInfo p = new AiPort.PromptInfo();
        p.promptId = 1000L; p.tenantId = tenantId; p.status = status.name(); p.name = "Summarise"; p.version = 1;
        p.variables.add(variable("claim_id", true));
        p.variables.add(variable("document_text", true));
        p.variables.add(variable("note", false));
        return Collections.singletonMap(1000L, p);
    }

    private static AiPromptDto.Variable variable(String name, boolean required) {
        AiPromptDto.Variable v = new AiPromptDto.Variable();
        v.name = name; v.required = required;
        return v;
    }

    private static PipelineField field(String tag, String type) {
        PipelineField f = new PipelineField(); f.setTagKey(tag); f.setLabel(tag); f.setFieldType(type); return f;
    }

    private static Pipeline pipelineWith(String variableMap, PipelineField... after) {
        Pipeline p = new Pipeline();
        p.setPipelineId("F1"); p.setPipelineName("Claims"); p.setSourceTaskTypeId(77L);
        PipelineField step = field("summary", "ai"); step.setPromptId(1000L); step.setVariableMap(variableMap); step.setOnError("continue");
        List<PipelineField> fields = new ArrayList<>(Arrays.asList(field("claim_id", "text"), field("document", "textarea"), step));
        fields.addAll(Arrays.asList(after));
        p.setFields(fields);
        return p;
    }

    @Test
    void aWellMappedStepSavesWithItsSettings() throws Exception {
        when(this.ai.prompts(any())).thenReturn(prompt(TENANT_A, Status.Active));
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
    void aRequiredVariableWithNoFieldIsRefused() throws Exception {
        when(this.ai.prompts(any())).thenReturn(prompt(TENANT_A, Status.Active));
        ResponseDto response = this.service.saveForm(pipelineWith("{\"claim_id\":\"claim_id\"}"));
        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("{{document_text}}");
        verify(this.pipelineRepository, never()).save(any());
    }

    @Test
    void aVariableReadingALaterFieldIsRefused() throws Exception {
        when(this.ai.prompts(any())).thenReturn(prompt(TENANT_A, Status.Active));
        ResponseDto response = this.service.saveForm(pipelineWith("{\"claim_id\":\"claim_id\",\"document_text\":\"later\"}", field("later", "text")));
        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("<later>").contains("before it");
    }

    @Test
    void anotherWorkspacesOrInactivePromptIsRefused() throws Exception {
        when(this.ai.prompts(any())).thenReturn(prompt(TENANT_B, Status.Active));
        assertThat(this.service.saveForm(pipelineWith("{\"claim_id\":\"claim_id\",\"document_text\":\"document\"}")).getMessage()).contains("cannot use");
        when(this.ai.prompts(any())).thenReturn(prompt(TENANT_A, Status.Inactive));
        assertThat(this.service.saveForm(pipelineWith("{\"claim_id\":\"claim_id\",\"document_text\":\"document\"}")).getMessage()).contains("not active");
        verify(this.pipelineRepository, never()).save(any());
    }

    @Test
    void aFileSourceNeedsAWorkerStepAndAServerStepCannotReadAWorkerStepsTag() throws Exception {
        when(this.ai.prompts(any())).thenReturn(prompt(TENANT_A, Status.Active));
        // file: on a server step
        ResponseDto onServer = this.service.saveForm(pipelineWith("{\"claim_id\":\"claim_id\",\"document_text\":\"file:document\"}"));
        assertThat(onServer.getMessage()).contains("only a step run in the worker");
        // the same on a worker step is fine
        Pipeline p = pipelineWith("{\"claim_id\":\"claim_id\",\"document_text\":\"file:document\"}");
        p.getFields().get(2).setRunIn("worker");
        assertThat(this.service.saveForm(p).getStatus()).isEqualTo("SUCCESS");
        // a server step after a worker step reading its tag
        Pipeline q = pipelineWith("{\"claim_id\":\"claim_id\",\"document_text\":\"document\"}");
        q.getFields().get(2).setRunIn("worker");
        PipelineField later = field("verdict", "ai"); later.setPromptId(1000L); later.setVariableMap("{\"claim_id\":\"claim_id\",\"document_text\":\"summary\"}");
        q.getFields().add(later);
        assertThat(this.service.saveForm(q).getMessage()).contains("runs before dispatch but reads <summary>");
    }

    /** The prompts are AI's (ADR-020): a form with several AI steps asks once, not once per step. */
    @Test
    void aFormAsksForAllItsPromptsInOneCall() throws Exception {
        when(this.ai.prompts(any())).thenReturn(prompt(TENANT_A, Status.Active));
        Pipeline p = pipelineWith("{\"claim_id\":\"claim_id\",\"document_text\":\"document\"}");
        PipelineField second = field("verdict", "ai"); second.setPromptId(1000L);
        second.setVariableMap("{\"claim_id\":\"claim_id\",\"document_text\":\"summary\"}");
        p.getFields().add(second);
        assertThat(this.service.saveForm(p).getStatus()).isEqualTo("SUCCESS");
        verify(this.ai, times(1)).prompts(any());
    }

    /** A form whose prompts cannot be checked is not saved on a guess. */
    @Test
    void whenTheAiServiceCannotAnswerTheFormIsRefusedNotSaved() throws Exception {
        when(this.ai.prompts(any())).thenThrow(new AiPort.AiUnavailableException("down", null));
        ResponseDto response = this.service.saveForm(pipelineWith("{\"claim_id\":\"claim_id\",\"document_text\":\"document\"}"));
        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("could not be checked right now");
        verify(this.pipelineRepository, never()).save(any());
    }
}
