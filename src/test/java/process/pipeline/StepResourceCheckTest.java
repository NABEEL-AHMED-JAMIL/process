package process.pipeline;

import org.junit.jupiter.api.Test;
import process.ai.AiPort;
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
