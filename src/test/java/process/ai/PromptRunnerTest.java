package process.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.AiPromptDto;
import process.model.pojo.AiModelConnection;
import process.model.pojo.AiPromptRun;
import process.model.repository.AiPromptRunRepository;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Rendering, the caps, the JSON check -- what a run does before and after the call. */
@ExtendWith(MockitoExtension.class)
public class PromptRunnerTest {

    @Mock private AiProviderGateway gateway;
    @Mock private AiPromptRunRepository runs;

    private static AiPromptDto.Variable var(String name, boolean required) {
        AiPromptDto.Variable v = new AiPromptDto.Variable();
        v.name = name; v.required = required; v.type = "text";
        return v;
    }

    private static AiModelConnection connection(Long budget) {
        AiModelConnection c = new AiModelConnection();
        c.setConnectionId(7L); c.setName("OpenAI · production"); c.setProvider("OpenAI"); c.setDefaultModel("gpt-4.1-mini");
        c.setMaxConcurrency(4); c.setDailyTokenBudget(budget);
        return c;
    }

    @Test
    void placeholdersAreReadInOrderOnce() {
        assertThat(PromptRunner.placeholders("Claim {{claim_id}}: {{ document_text }} -- {{claim_id}}"))
            .containsExactly("claim_id", "document_text");
    }

    @Test
    void renderFillsEveryPlaceholderAndRefusesAnEmptyRequiredOne() {
        Map<String, String> values = new HashMap<>();
        values.put("claim_id", "CLM-1");
        assertThat(PromptRunner.render("Claim {{claim_id}} / {{note}}", Arrays.asList(var("claim_id", true), var("note", false)), values))
            .isEqualTo("Claim CLM-1 / ");
        assertThatThrownBy(() -> PromptRunner.render("{{claim_id}}", Collections.singletonList(var("claim_id", true)), new HashMap<>()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("claim_id");
    }

    @Test
    void theDailyBudgetFailsTheRunBeforeAnyCall() throws Exception {
        when(this.runs.tokensSince(eq(7L), any())).thenReturn(5_000L);
        when(this.runs.save(any(AiPromptRun.class))).thenAnswer(inv -> inv.getArgument(0));
        PromptRunner runner = new PromptRunner(this.gateway, this.runs);
        PromptRunner.Job job = new PromptRunner.Job();
        job.connection = connection(5_000L); job.template = "hello"; job.kind = "try"; job.outputMode = "text";

        AiPromptRun row = runner.run(job);

        assertThat(row.getStatus()).isEqualTo("failed");
        assertThat(row.getError()).contains("budget");
        verify(this.gateway, never()).chat(any());
    }

    @Test
    void jsonOutputGetsOneRepairRoundThenFails() throws Exception {
        when(this.runs.save(any(AiPromptRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(this.gateway.chat(any()))
            .thenReturn(new AiProviderGateway.ChatAnswer("```json\n{\"diagnosis\": \"x\"}\n```", 10, 5))
            .thenReturn(new AiProviderGateway.ChatAnswer("{\"diagnosis\": \"x\", \"flags\": []}", 12, 6));
        PromptRunner runner = new PromptRunner(this.gateway, this.runs);
        PromptRunner.Job job = new PromptRunner.Job();
        job.connection = connection(null); job.template = "go"; job.kind = "try"; job.outputMode = "json";
        job.outputSchema = "{\"required\":[\"diagnosis\",\"flags\"]}";

        AiPromptRun row = runner.run(job);

        assertThat(row.getStatus()).isEqualTo("ok");
        assertThat(row.getOutput()).isEqualTo("{\"diagnosis\": \"x\", \"flags\": []}");
        // Both rounds are paid for and both are counted.
        assertThat(row.getTokensIn()).isEqualTo(22);
        verify(this.gateway, times(2)).chat(any());
    }

    @Test
    void aRateLimitIsRetriedAndAnAuthFailureIsNot() throws Exception {
        when(this.runs.save(any(AiPromptRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(this.gateway.chat(any()))
            .thenThrow(new AiProviderGateway.ProviderException(429, "HTTP 429: slow down"))
            .thenReturn(new AiProviderGateway.ChatAnswer("fine", 1, 1));
        PromptRunner runner = new PromptRunner(this.gateway, this.runs);
        PromptRunner.Job job = new PromptRunner.Job();
        job.connection = connection(null); job.template = "go"; job.kind = "run"; job.outputMode = "text";

        AiPromptRun row = runner.run(job);
        assertThat(row.getStatus()).isEqualTo("ok");
        assertThat(row.getAttempts()).isEqualTo(2);

        reset(this.gateway);
        when(this.gateway.chat(any())).thenThrow(new AiProviderGateway.ProviderException(401, "HTTP 401: bad key"));
        AiPromptRun refused = runner.run(job);
        assertThat(refused.getStatus()).isEqualTo("failed");
        assertThat(refused.getAttempts()).isEqualTo(1);
    }
}
