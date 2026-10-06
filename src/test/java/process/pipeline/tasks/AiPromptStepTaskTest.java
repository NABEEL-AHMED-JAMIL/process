package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.ai.AiPort;
import process.pipeline.Dataset;
import process.pipeline.backing.Fakes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/**
 * MIG-245: the AI step runs a saved prompt once per row through ai-service, with the row's values and, when it names an
 * image column, that row's image read from the workspace's bucket. The model's answer lands in a column, and fields of a
 * JSON answer in columns of their own. Models run in ai-service; Core never holds a key.
 */
class AiPromptStepTaskTest {

    private final Fakes.Buckets buckets = new Fakes.Buckets();
    private final AiPort ai = mock(AiPort.class);
    private final AiPromptStepTask task = new AiPromptStepTask(this.ai, this.buckets);
    private final List<List<AiPort.Image>> sentImages = new ArrayList<>();

    private static AiPort.StepResult answered(String output) {
        AiPort.StepResult ok = new AiPort.StepResult();
        ok.status = "ok";
        ok.output = output;
        ok.model = "gemma3:4b";
        return ok;
    }

    private void answer(String output) {
        when(this.ai.runRowStep(anyLong(), anyLong(), anyString(), anyString(), any(), anyMap(), any(), any(), any()))
            .thenAnswer(inv -> {
                List<AiPort.Image> images = inv.getArgument(6);
                this.sentImages.add(images);
                return answered(output);
            });
    }

    private TaskContext imageContext(Object... more) {
        Object[] base = {"promptId", 41, "values", config("patient", "{{patient_id}}"),
            "image", config("bucket", "wounds", "keyColumn", "photo"),
            "fields", Arrays.asList(config("path", "stage", "target", "stage"), config("path", "length_cm", "target", "length_cm"))};
        Object[] settings = new Object[base.length + more.length];
        System.arraycopy(base, 0, settings, 0, base.length);
        System.arraycopy(more, 0, settings, base.length, more.length);
        return TaskContext.of(config(settings), Arrays.asList(row("patient_id", "P-1", "photo", "in/p1.jpg"),
            row("patient_id", "P-2", "photo", "in/p2.png")));
    }

    @Test
    void eachRowIsOneCallWithItsImageAndTheAnswersFieldsBecomeColumns() throws Exception {
        this.buckets.objects.put("wounds/in/p1.jpg", "jpeg-bytes".getBytes(StandardCharsets.UTF_8));
        this.buckets.objects.put("wounds/in/p2.png", "png-bytes".getBytes(StandardCharsets.UTF_8));
        this.answer("{\"stage\":\"2\",\"length_cm\":3.1}");

        Dataset out = this.task.run(this.imageContext()).getOutput();

        assertThat(out.getColumns()).containsExactly("patient_id", "photo", "ai_output", "stage", "length_cm");
        assertThat(out.getRows()).hasSize(2);
        assertThat(out.getRows().get(0)).containsEntry("stage", "2").containsEntry("length_cm", 3.1);
        assertThat(this.sentImages).hasSize(2);
        assertThat(this.sentImages.get(0).get(0).mediaType).isEqualTo("image/jpeg");
        assertThat(this.sentImages.get(0).get(0).base64).isEqualTo(Base64.getEncoder().encodeToString("jpeg-bytes".getBytes(StandardCharsets.UTF_8)));
        assertThat(this.sentImages.get(1).get(0).mediaType).isEqualTo("image/png");
        // Each row is its own run in ai-service (tag#item), so a retried run reuses that row's answer.
        verify(this.ai).runRowStep(eq(TaskContext.TENANT), anyLong(), eq("step"), eq("1"), eq(41L), anyMap(), anyList(), isNull(), isNull());
        verify(this.ai).runRowStep(eq(TaskContext.TENANT), anyLong(), eq("step"), eq("2"), eq(41L), anyMap(), anyList(), isNull(), isNull());
    }

    @Test
    void aTextStepSendsNoImagesAndKeepsTheAnswerAsText() throws Exception {
        this.answer("A short summary.");
        TaskContext context = TaskContext.of(config("promptId", 41, "values", config("note", "{{note}}"), "outputColumn", "summary"),
            Arrays.asList(row("note", "Healing well")));

        Dataset out = this.task.run(context).getOutput();

        assertThat(out.getColumns()).containsExactly("note", "summary");
        assertThat(out.getRows().get(0)).containsEntry("summary", "A short summary.");
        assertThat(this.sentImages.get(0)).isNull();
    }

    @Test
    void aRowWhoseImageIsNotAnImageFailsItByTheRule() throws Exception {
        this.buckets.objects.put("wounds/in/p1.jpg", "jpeg-bytes".getBytes(StandardCharsets.UTF_8));
        this.buckets.objects.put("wounds/in/p2.png", "png-bytes".getBytes(StandardCharsets.UTF_8));
        this.answer("{}");
        TaskContext context = TaskContext.of(config("promptId", 41, "image", config("bucket", "wounds", "keyColumn", "photo"), "onError", "skip"),
            Arrays.asList(row("photo", "in/p1.jpg"), row("photo", "in/notes.pdf"), row("photo", "")));

        Dataset out = this.task.run(context).getOutput();

        assertThat(out.getRows()).hasSize(1);
        assertThat(this.sentImages).hasSize(1);
    }

    @Test
    void aFailedCallFailsTheStepByDefaultAndOtherwiseSkipsOrEmptiesTheRow() throws Exception {
        this.buckets.objects.put("wounds/in/p1.jpg", "a".getBytes(StandardCharsets.UTF_8));
        this.buckets.objects.put("wounds/in/p2.png", "b".getBytes(StandardCharsets.UTF_8));
        when(this.ai.runRowStep(anyLong(), anyLong(), anyString(), anyString(), any(), anyMap(), any(), any(), any()))
            .thenReturn(AiPort.StepResult.failed("The model is not running."));

        assertThatThrownBy(() -> this.task.run(this.imageContext())).hasMessageContaining("The model is not running.");
        assertThat(this.task.run(this.imageContext("onError", "skip")).getOutput().size()).isZero();
        Dataset kept = this.task.run(this.imageContext("onError", "null")).getOutput();
        assertThat(kept.getRows()).hasSize(2);
        assertThat(kept.getRows().get(0).get("stage")).isNull();
    }

    @Test
    void tooManyRowsFailBeforeAnyCall() {
        TaskContext context = TaskContext.of(config("promptId", 41, "maxCalls", 1), Arrays.asList(row("a", "1"), row("a", "2")));

        assertThatThrownBy(() -> this.task.run(context)).hasMessage("The input needs 2 calls; this step makes at most 1 (maxCalls).");
        verify(this.ai, never()).runRowStep(anyLong(), anyLong(), anyString(), anyString(), any(), anyMap(), any(), any(), any());
    }
}
