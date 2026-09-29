package process.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.Pipeline;
import process.model.repository.PipelineRepository;
import process.security.TenantContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MIG-230: checking a draft, and when a save is a new version, no version, or a refusal. */
class PipelineDefinitionServiceTest {

    private static final long TENANT = 41L;
    private static final long KEY = 9001L;

    private final PipelineRepository pipelines = mock(PipelineRepository.class);
    private final PipelineDefinitionStore store = mock(PipelineDefinitionStore.class);
    private final StepTasks tasks = Definitions.builtInTasks();
    private final PipelineDefinitionService service = new PipelineDefinitionService(this.pipelines, this.store,
        new DefinitionValidator(this.tasks), this.tasks);

    @BeforeEach
    void signIn() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 7L, "admin@example");
        Pipeline pipeline = new Pipeline();
        pipeline.setPipelineKey(KEY);
        pipeline.setTenantId(TENANT);
        pipeline.setPipelineId("F768927");
        when(this.pipelines.findByPipelineKeyAndStatusNot(KEY, Status.Delete)).thenReturn(Optional.of(pipeline));
        when(this.store.latest(KEY)).thenReturn(Optional.empty());
        when(this.store.versions(KEY)).thenReturn(Collections.emptyList());
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    private static PipelineDefinitionService.DefinitionRequest draft(String text) {
        PipelineDefinitionService.DefinitionRequest request = new PipelineDefinitionService.DefinitionRequest();
        request.setPipelineKey(KEY);
        request.setText(text);
        return request;
    }

    private static final String GOOD = "version: 1\nsteps:\n  - {key: read, task: sample, config: {rows: [{id: 1}]}}\n";

    @Test
    @SuppressWarnings("unchecked")
    void aDraftWithProblemsIsAnsweredWithEveryProblemAndNothingIsSaved() {
        ResponseDto answer = this.service.validate(draft("version: 2\nsteps:\n  - {key: Read, task: nosuch}\n"));
        assertThat(answer.getStatus()).isEqualTo("ERROR");
        List<DefinitionProblem> problems = (List<DefinitionProblem>) ((Map<String, Object>) answer.getData()).get("problems");
        assertThat(problems).extracting(DefinitionProblem::getPath).containsExactly("version", "steps[0].key", "steps[0].task");

        assertThat(this.service.save(draft("version: 2\nsteps: []\n")).getStatus()).isEqualTo("ERROR");
        assertThat(this.service.save(draft("{not json")).getStatus()).isEqualTo("ERROR");
        verify(this.store, never()).save(anyLong(), anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aGoodDraftIsAnsweredWithItsJsonAndYaml() {
        ResponseDto answer = this.service.validate(draft(GOOD));
        assertThat(answer.getStatus()).isEqualTo("SUCCESS");
        Map<String, Object> payload = (Map<String, Object>) answer.getData();
        assertThat(payload).containsEntry("valid", true).containsEntry("legacy", false);
        assertThat((String) payload.get("json")).contains("\"task\" : \"sample\"");
        assertThat((String) payload.get("yaml")).contains("task: sample");
    }

    @Test
    void aSaveStoresTheCanonicalJsonWhicheverFormatItCameIn() throws Exception {
        PipelineDefinitionStore.Stored saved = new PipelineDefinitionStore.Stored();
        saved.id = 1001L;
        saved.version = 1;
        when(this.store.save(eq(KEY), anyString(), eq(7L))).thenReturn(saved);
        ResponseDto answer = this.service.save(draft(GOOD));
        assertThat(answer.getMessage()).isEqualTo("Saved as version 1.");
        verify(this.store).save(KEY, DefinitionCodec.toJson(DefinitionCodec.fromYaml(GOOD)), 7L);
    }

    @Test
    void savingWhatIsAlreadyTheLatestOrTheLegacyWrapWritesNoVersion() throws Exception {
        assertThat(this.service.save(draft(DefinitionCodec.toYaml(PipelineDefinition.legacy("F768927")))).getMessage())
            .isEqualTo("Unchanged: the pipeline already runs as its legacy step.");
        PipelineDefinitionStore.Stored latest = new PipelineDefinitionStore.Stored();
        latest.version = 3;
        latest.json = DefinitionCodec.toJson(DefinitionCodec.fromYaml(GOOD));
        when(this.store.latest(KEY)).thenReturn(Optional.of(latest));
        assertThat(this.service.save(draft(GOOD)).getMessage()).isEqualTo("Unchanged: version 3 is this definition.");
        verify(this.store, never()).save(anyLong(), anyString(), any());
    }

    @Test
    void twoSavesAtOnceAnswerTheLoserInWords() {
        when(this.store.save(eq(KEY), anyString(), any())).thenThrow(new DuplicateKeyException("ux_pipeline_definition_version"));
        assertThat(this.service.save(draft(GOOD)).getMessage()).startsWith("Someone else saved this pipeline's definition");
    }

    @Test
    void aPlatformPipelineOrAnotherWorkspacesIsNotFoundOrKept() {
        Pipeline platform = new Pipeline();
        platform.setPipelineKey(9002L);
        when(this.pipelines.findByPipelineKeyAndStatusNot(9002L, Status.Delete)).thenReturn(Optional.of(platform));
        PipelineDefinitionService.DefinitionRequest theirs = draft(GOOD);
        theirs.setPipelineKey(9002L);
        assertThat(this.service.save(theirs).getMessage()).isEqualTo("That pipeline no longer exists.");
        assertThat(this.service.read(null).getMessage()).isEqualTo("That pipeline no longer exists.");
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example");
        assertThat(this.service.save(theirs).getMessage()).isEqualTo("A platform pipeline keeps its legacy definition.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theTaskListNamesEachTaskAndWhetherTheEngineRunsIt() {
        List<Map<String, Object>> list = (List<Map<String, Object>>) this.service.tasks().getData();
        assertThat(list).extracting(entry -> entry.get("code")).containsExactly("legacy", "sample", "select");
        assertThat(list.get(0)).containsEntry("runsInEngine", false);
    }
}
