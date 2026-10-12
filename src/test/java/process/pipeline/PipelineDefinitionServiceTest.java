package process.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.Pipeline;
import process.model.repository.PipelineRepository;
import process.pipeline.backing.Fakes;
import process.pipeline.registry.InMemoryTaskOverrideStore;
import process.pipeline.registry.TaskRegistry;
import process.pipeline.tasks.MeasureImageStepTask;
import process.security.TenantContext;
import process.util.UserNameResolver;

import java.util.Arrays;
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
    private final InMemoryTaskOverrideStore overrides = new InMemoryTaskOverrideStore();
    private final TaskRegistry registry = new TaskRegistry(this.tasks, this.overrides);
    private final UserNameResolver names = mock(UserNameResolver.class);
    private final PipelineDefinitionService service = new PipelineDefinitionService(this.pipelines, this.store,
        new DefinitionValidator(this.registry), this.registry, this.overrides, this.names);

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

    /**
     * 2026-10-06: a save writes each measure step's target. One the previous version had without a target (saved before
     * targets existed) keeps red_region, the rule it was saved under; a new step gets contrast, the default.
     */
    @Test
    void aSaveWritesEachMeasureStepsTargetAndKeepsRedOnSkinForAStepSavedWithout() throws Exception {
        TaskRegistry registry = new TaskRegistry(Definitions.builtInTasks(new MeasureImageStepTask(new Fakes.Buckets())), this.overrides);
        PipelineDefinitionService measuring = new PipelineDefinitionService(this.pipelines, this.store, new DefinitionValidator(registry),
            registry, this.overrides, this.names);
        String measure = "task: measure_image, input: read, config: {image: {bucket: photos, keyColumn: image_key}}}\n";
        String before = "version: 1\nsteps:\n  - {key: read, task: sample, config: {rows: [{image_key: a.png}]}}\n  - {key: size, " + measure;
        PipelineDefinitionStore.Stored latest = new PipelineDefinitionStore.Stored();
        latest.version = 1;
        latest.json = DefinitionCodec.toJson(DefinitionCodec.fromYaml(before));
        when(this.store.latest(KEY)).thenReturn(Optional.of(latest));
        when(this.store.save(eq(KEY), anyString(), eq(7L))).thenReturn(new PipelineDefinitionStore.Stored());

        ResponseDto answer = measuring.save(draft(before + "  - {key: size_2, " + measure));

        assertThat(answer.getStatus()).as(answer.getMessage()).isEqualTo("SUCCESS");
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(this.store).save(eq(KEY), json.capture(), eq(7L));
        PipelineDefinition saved = DefinitionCodec.fromJson(json.getValue());
        assertThat(saved.getSteps().get(1).getConfig()).containsEntry("target", "red_region");
        assertThat(saved.getSteps().get(2).getConfig()).containsEntry("target", "contrast");
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
    @SuppressWarnings("unchecked")
    void eachVersionNamesWhoSavedIt() {
        PipelineDefinitionStore.Stored second = new PipelineDefinitionStore.Stored();
        second.version = 2;
        second.createdBy = 4537L;
        PipelineDefinitionStore.Stored first = new PipelineDefinitionStore.Stored();
        first.version = 1;
        PipelineDefinitionStore.Stored gone = new PipelineDefinitionStore.Stored();
        gone.version = 0;
        gone.createdBy = 12L;
        when(this.store.versions(KEY)).thenReturn(Arrays.asList(second, first, gone));
        when(this.names.namesFor(any())).thenReturn(Collections.singletonMap(4537L, "Claude Demo Admin"));

        Map<String, Object> payload = (Map<String, Object>) this.service.read(KEY).getData();
        List<Map<String, Object>> versions = (List<Map<String, Object>>) payload.get("versions");

        assertThat(versions).extracting(v -> v.get("createdByName")).containsExactly("Claude Demo Admin", null, null);
        assertThat(versions).extracting(v -> v.get("createdBy")).containsExactly(4537L, null, 12L);
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
    void theTaskListIsTheRegistryAsMyWorkspaceSeesItWithALegacyEntryPerPipeline() {
        Pipeline claims = new Pipeline();
        claims.setPipelineKey(KEY);
        claims.setPipelineId("F768927");
        claims.setPipelineName("Claims intake");
        when(this.pipelines.findAllByTenantIdAndStatusNotOrderByPipelineKeyDesc(TENANT, Status.Delete))
            .thenReturn(Collections.singletonList(claims));
        this.overrides.set(TENANT, "select", false, 7L);

        ResponseDto answer = this.service.tasks();
        List<Map<String, Object>> list = (List<Map<String, Object>>) answer.getData();

        assertThat(answer.getMessage()).isEqualTo("3 step task(s) and 1 legacy pipeline(s).");
        assertThat(list).extracting(entry -> entry.get("code")).containsExactly("legacy", "sample", "select", "legacy");
        assertThat(list.get(0)).containsEntry("runsInEngine", false).containsEntry("kind", "Legacy").containsEntry("enabled", true)
            .containsEntry("overridable", false).containsEntry("aiToolName", "run_legacy_pipeline").containsKey("configSchema");
        assertThat(list.get(2)).containsEntry("enabled", false).containsEntry("disabledReason", "Switched off in this workspace.");
        assertThat(list.get(3)).containsEntry("name", "Legacy: Claims intake").containsEntry("pipelineId", "F768927")
            .containsEntry("pipelineKey", KEY).containsEntry("config", Collections.singletonMap("pipelineId", "F768927"));
    }

    @Test
    void anAdminSwitchesATaskForTheWorkspaceButNeverLegacy() {
        PipelineDefinitionService.TaskSwitchRequest off = new PipelineDefinitionService.TaskSwitchRequest();
        off.setCode("sample");
        off.setEnabled(false);
        assertThat(this.service.switchTask(off).getMessage()).isEqualTo("'sample' is switched off in this workspace.");
        assertThat(this.overrides.overrides(TENANT)).containsEntry("sample", false);
        assertThat(this.service.validate(draft(GOOD)).getMessage()).contains("the task 'sample' is disabled in this workspace");
        assertThat(this.service.save(draft(GOOD)).getMessage()).contains("the task 'sample' is disabled in this workspace");

        off.setEnabled(null);
        assertThat(this.service.switchTask(off).getMessage()).isEqualTo("'sample' is back to its default.");
        assertThat(this.overrides.overrides(TENANT)).isEmpty();

        PipelineDefinitionService.TaskSwitchRequest legacy = new PipelineDefinitionService.TaskSwitchRequest();
        legacy.setCode("legacy");
        legacy.setEnabled(false);
        assertThat(this.service.switchTask(legacy).getMessage()).isEqualTo("'legacy' cannot be switched: existing pipelines always stay runnable.");
        legacy.setCode("nosuch");
        assertThat(this.service.switchTask(legacy).getMessage()).isEqualTo("No task 'nosuch' is registered.");
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example");
        assertThat(this.service.switchTask(off).getMessage()).isEqualTo("Tasks are switched in a workspace; this caller has none.");
    }
}
