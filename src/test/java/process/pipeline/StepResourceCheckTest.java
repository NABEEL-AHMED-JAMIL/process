package process.pipeline;

import org.junit.jupiter.api.Test;
import process.ai.AiPort;
import process.model.dto.AiPromptDto;
import process.identity.IdentityPort;
import process.pipeline.backing.IntegrationNames;
import process.pipeline.backing.ReportTemplates;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.step;

/**
 * AI hard-workflow test (2026-10-13): a pipeline is saved only when what its steps name in other services is there, the
 * workspace's, and of the kind its setting wants -- a prompt's id as a render_pdf templateId is refused naming the prompt --
 * and an API request's variables its environment leaves open are set. A service that cannot answer refuses nothing.
 */
class StepResourceCheckTest {

    private static final long RIVERSIDE = 2960L;

    private final AiPort ai = mock(AiPort.class);
    private final IntegrationNames integration = mock(IntegrationNames.class);
    private final ReportTemplates templates = mock(ReportTemplates.class);
    private final StepResourceCheck check = new StepResourceCheck(new AllTasks().tasks());

    StepResourceCheckTest() {
        this.check.useAi(this.ai);
        this.check.useIntegration(this.integration);
        this.check.useTemplates(this.templates);
    }

    private static AiPort.PromptInfo prompt(long id, String name, Long tenant) {
        AiPort.PromptInfo p = new AiPort.PromptInfo();
        p.promptId = id;
        p.name = name;
        p.tenantId = tenant;
        p.status = "Active";
        return p;
    }

    private void riverside() throws Exception {
        Map<Long, AiPort.PromptInfo> prompts = new LinkedHashMap<>();
        prompts.put(1075L, prompt(1075L, "Patient 360", RIVERSIDE));
        prompts.put(1077L, prompt(1077L, "Drug safety weekly", RIVERSIDE));
        prompts.put(9000L, prompt(9000L, "Another workspace's", 2924L));
        when(this.ai.prompts(anyCollection())).thenAnswer(i -> {
            Map<Long, AiPort.PromptInfo> asked = new LinkedHashMap<>();
            for (Object id : (Collection<?>) i.getArgument(0)) {
                if (prompts.containsKey((Long) id)) asked.put((Long) id, prompts.get((Long) id));
            }
            return asked;
        });
        when(this.templates.templatesOf(eq(RIVERSIDE), anyCollection())).thenReturn(Collections.singletonMap(31L, "Lab summary"));
        IntegrationNames.Names names = new IntegrationNames.Names();
        IntegrationNames.RequestName loinc = new IntegrationNames.RequestName();
        loinc.name = "FHIR: observations by LOINC code";
        loinc.needsVariables = Arrays.asList("loinc");
        names.requests.put(1084L, loinc);
        when(this.integration.describe(eq(RIVERSIDE), anyCollection(), anyCollection())).thenReturn(names);
    }

    private List<String> problems(PipelineDefinition definition) {
        return this.check.problems(definition, RIVERSIDE).stream().map(p -> p.getPath() + ": " + p.getMessage()).collect(Collectors.toList());
    }

    @Test
    void eachIdIsTheWorkspacesAndOfItsSettingsKind() throws Exception {
        this.riverside();
        List<String> problems = this.problems(Definitions.of(
            step("labs", "read_api", config("requestId", 1084)),
            step("gone", "read_api", config("requestId", 1099)),
            step("summary", "ai_prompt", config("promptId", 1075)),
            step("theirs", "ai_prompt", config("promptId", 9000)),
            step("pdf", "render_pdf", config("fileName", "x", "templateId", 1077)),
            step("good_pdf", "render_pdf", config("fileName", "y", "templateId", 31))));
        assertThat(problems).containsExactlyInAnyOrder(
            "steps[3].config.promptId: 9000 is not an active AI prompt of this workspace",
            "steps[4].config.templateId: 1077 is not a report template of this workspace (it is the AI prompt \"Drug safety weekly\"); "
                + "leave it empty for the default layout",
            "steps[1].config.requestId: 1099 is not a saved API request of this workspace",
            "steps[0].config.variables.loinc: the API request 1084 \"FHIR: observations by LOINC code\" uses {{loinc}}, which its "
                + "environment does not set: give it a value here (or {{column}} from the rows)");
    }

    @Test
    void aVariableGivenOrAnotherEnvironmentIsEnough() throws Exception {
        this.riverside();
        assertThat(this.problems(Definitions.of(step("labs", "read_api", config("requestId", 1084, "variables", config("loinc", "4548-4"))))))
            .isEmpty();
        assertThat(this.problems(Definitions.of(step("labs", "read_api", config("requestId", 1084, "environmentId", 7))))).isEmpty();
    }

    /**
     * ZT-S4: Validate warns -- never refuses -- of a required prompt variable the AI step does not map in Variables: by name
     * when the rows it reads (a sample step) have no such column, as "needs a column" when the rows are not known; a mapped
     * one, an optional one, or a column the sample has: no warning.
     */
    @Test
    void anUnmappedRequiredPromptVariableIsAWarning() throws Exception {
        this.riverside();
        AiPort.PromptInfo discharge = prompt(1080L, "Discharge", RIVERSIDE);
        AiPromptDto.Variable reason = new AiPromptDto.Variable();
        reason.name = "reason";
        reason.required = true;
        AiPromptDto.Variable notes = new AiPromptDto.Variable();
        notes.name = "notes";
        notes.required = false;
        discharge.variables = Arrays.asList(reason, notes);
        when(this.ai.prompts(anyCollection())).thenReturn(Collections.singletonMap(1080L, discharge));
        PipelineDefinition definition = Definitions.of(
            step("rows", "sample", config("rows", Arrays.asList(config("name", "A")))),
            step("blind", "ai_prompt", config("promptId", 1080)),
            step("rows2", "sample", config("rows", Arrays.asList(config("reason", "Foot wound")))),
            step("column", "ai_prompt", config("promptId", 1080)),
            step("mapped", "ai_prompt", config("promptId", 1080, "values", config("reason", "{{diagnosis}}"))),
            step("read", "read_api", config("requestId", 1084, "variables", config("loinc", "4548-4"))),
            step("unknown", "ai_prompt", config("promptId", 1080)));

        List<String> warnings = this.check.warnings(definition, RIVERSIDE).stream().map(p -> p.getPath() + ": " + p.getMessage())
            .collect(Collectors.toList());

        assertThat(warnings).containsExactly(
            "steps[1].config.values: Prompt variable 'reason' is required, not mapped in Variables, and the input has no column named "
                + "reason: map it, or every row's call fails.",
            "steps[6].config.values: Prompt variable 'reason' is required and not mapped in Variables: each row needs a column named "
                + "reason, or that row's call fails.");
        assertThat(this.problems(definition)).isEmpty();
    }

    /**
     * ZT-M15: the teams a definition names as reviewers -- its review's and an Approval step's -- are active teams of the
     * pipeline's workspace; another workspace's (or an archived, or unknown) team is refused by name. Identity silent: not
     * checked, as the other references.
     */
    @Test
    void reviewerTeamsAreTheWorkspacesOwn() {
        IdentityPort identity = mock(IdentityPort.class);
        when(identity.isActiveTeamOf(RIVERSIDE, 8L)).thenReturn(true);
        when(identity.isActiveTeamOf(RIVERSIDE, 9L)).thenReturn(false);
        when(identity.isActiveTeamOf(RIVERSIDE, 10L)).thenReturn(null);
        this.check.useIdentity(identity);
        PipelineDefinition definition = Definitions.of(
            step("ours", "approval", config("title", "Approve", "reviewers", config("kind", "team", "value", "8"))),
            step("theirs", "approval", config("title", "Approve", "reviewers", config("kind", "team", "value", "9"))),
            step("unknown", "approval", config("title", "Approve", "reviewers", config("kind", "team", "value", "10"))),
            step("role", "approval", config("title", "Approve", "reviewers", config("kind", "role", "value", "TENANT_USER"))));
        PipelineDefinition.Settings settings = new PipelineDefinition.Settings();
        PipelineDefinition.Review review = PipelineDefinition.Review.of(Collections.singletonList("internal"));
        review.setReviewers(PipelineDefinition.Reviewers.of(PipelineDefinition.Reviewers.TEAM, "9"));
        settings.setReview(review);
        definition.setSettings(settings);
        assertThat(this.problems(definition)).containsExactlyInAnyOrder(
            "settings.review.reviewers.value: That team is not in this workspace. (team 9)",
            "steps[1].config.reviewers.value: That team is not in this workspace. (team 9)");
    }

    @Test
    void aServiceThatCannotAnswerRefusesNothing() throws Exception {
        when(this.ai.prompts(anyCollection())).thenThrow(new AiPort.AiUnavailableException("down", null));
        when(this.templates.templatesOf(anyLong(), anyCollection())).thenThrow(new IOException("down"));
        when(this.integration.describe(anyLong(), anyCollection(), any())).thenThrow(new IOException("down"));
        assertThat(this.problems(Definitions.of(step("labs", "read_api", config("requestId", 1084)),
            step("pdf", "render_pdf", config("fileName", "x", "templateId", 1077)), step("ai", "ai_prompt", config("promptId", 1))))).isEmpty();
        assertThat(this.check.problems(PipelineDefinition.legacy("P"), RIVERSIDE)).isEmpty();
    }
}
