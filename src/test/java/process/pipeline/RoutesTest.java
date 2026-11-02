package process.pipeline;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.step;

/** MIG-450: which branch a step is in, and what a Merge stacks. */
class RoutesTest {

    private static PipelineDefinition.Step reads(PipelineDefinition.Step step, String input) {
        step.setInput(input);
        return step;
    }

    private final List<PipelineDefinition.Step> steps = Arrays.asList(
        step("read", "sample"),
        step("triage", "route", config("branches", Collections.singletonList(config("name", "urgent", "conditions",
            Collections.singletonList(config("column", "p", "operator", "eq", "value", "urgent")))), "otherwise", "routine")),
        reads(step("opinion", "call_api"), "triage.urgent"),
        step("shape", "select"),
        reads(step("tag", "compute"), "triage.routine"),
        step("together", "merge", config("route", "triage")),
        step("save", "save_file"));

    @Test
    void aStepIsInTheBranchItReadsUntilTheMerge() {
        assertThat(Routes.branchNames(this.steps.get(1))).containsExactly("urgent", "routine");
        assertThat(Routes.branchOfSteps(this.steps)).containsExactly(
            Map.entry("opinion", "triage.urgent"), Map.entry("shape", "triage.urgent"),
            Map.entry("tag", "triage.routine"));
    }

    @Test
    void aMergeStacksEachBranchsLastOutputElseItsOwnRows() {
        assertThat(Routes.heads(this.steps, 5, "triage", new HashSet<>(Arrays.asList("opinion", "shape", "tag"))))
            .extracting(h -> h.branch + "=" + h.reads + "/" + h.column).containsExactly("urgent=shape/branch", "routine=tag/branch");
        // shape failed and continued: the branch's last output is opinion's; tag never ran: the branch's own rows.
        assertThat(Routes.heads(this.steps, 5, "triage", new HashSet<>(Collections.singletonList("opinion"))))
            .extracting(h -> h.branch + "=" + h.reads).containsExactly("urgent=opinion", "routine=triage.routine");
        assertThat(Routes.heads(this.steps, 5, "read", Collections.emptySet())).isEmpty();
    }
}
