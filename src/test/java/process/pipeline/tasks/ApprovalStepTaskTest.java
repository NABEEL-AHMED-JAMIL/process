package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepResult;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/**
 * MIG-346: the Approval step asks; the engine pauses the run on its answer. Its task's title, instructions and fields fill
 * from the run and the first row; its reviewers are its own, else (null here) the pipeline's; its time limit is bounded.
 */
class ApprovalStepTaskTest {

    private final ApprovalStepTask task = new ApprovalStepTask();

    @Test
    void itAsksWithTheRunsAndTheFirstRowsValues() throws Exception {
        TaskContext context = TaskContext.of(config("title", "Send {{rows}} lab results ({{abnormal}} abnormal)?",
            "instructions", "Check run {{run}} before it goes out.", "fields", config("Abnormal", "{{abnormal}}"),
            "reviewers", config("kind", "role", "value", "TENANT_ADMIN"), "expiresAfterHours", 24),
            Arrays.asList(row("abnormal", 7L, "total", 120L), row("abnormal", 7L, "total", 120L)));
        StepResult result = this.task.run(context);
        assertThat(result.isAwaiting()).isTrue();
        assertThat(result.getOutput()).isNull();
        assertThat(result.getRecordsOut()).isEqualTo(2L);
        StepResult.ApprovalAsk ask = result.getApproval();
        assertThat(ask.title).isEqualTo("Send 2 lab results (7 abnormal)?");
        assertThat(ask.instructions).isEqualTo("Check run " + TaskContext.RUN + " before it goes out.");
        assertThat(ask.fields).containsEntry("Abnormal", "7");
        assertThat(ask.reviewersKind).isEqualTo("role");
        assertThat(ask.reviewersValue).isEqualTo("TENANT_ADMIN");
        assertThat(ask.expiresAfterHours).isEqualTo(24);
    }

    @Test
    void withNothingSetItAsksTheDefaultQuestionOfThePipelinesReviewers() throws Exception {
        StepResult result = this.task.run(TaskContext.of(config(), Arrays.asList(row("a", 1))));
        assertThat(result.getApproval().title).isEqualTo("Approve run " + TaskContext.RUN + " to continue (1 rows at step step)");
        assertThat(result.getApproval().reviewersKind).isNull();
        assertThat(result.getApproval().expiresAfterHours).isEqualTo(72);
    }

    @Test
    void reviewersAreARoleOrATeam() {
        assertThat(this.task.check(config("reviewers", config("kind", "role", "value", "OWNER"))))
            .containsExactly(new DefinitionProblem("reviewers.value", "a role: one of [TENANT_ADMIN, TENANT_USER]"));
        assertThat(this.task.check(config("reviewers", config("kind", "team", "value", "abc"))))
            .containsExactly(new DefinitionProblem("reviewers.value", "a team: the team's id"));
        assertThat(this.task.check(config("reviewers", config("kind", "team", "value", "88")))).isEmpty();
        assertThat(this.task.check(config("reviewers", config()))).isEmpty();
    }
}
