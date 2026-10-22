package process.pipeline.tasks;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-420: an AI prompt's call for an item is keyed by the item and what it asks, so a retried item that asks the same
 * reuses its answer (never bought twice) and one that now asks something else gets a fresh answer, never the stale one.
 */
class AiPromptItemTagTest {

    private static Map<String, String> asked(String transcript) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("transcript", transcript);
        v.put("file_name", "call-b.m4a");
        return v;
    }

    @Test
    void anItemsCallIsKeyedByWhatItAsks() {
        String same = AiPromptStepTask.itemTag("i0123456789abcdef", asked("Patient reports dizziness."), null);
        assertThat(AiPromptStepTask.itemTag("i0123456789abcdef", asked("Patient reports dizziness."), null)).isEqualTo(same);
        assertThat(AiPromptStepTask.itemTag("i0123456789abcdef", asked(""), null)).as("an empty transcript then, a real one now")
            .isNotEqualTo(same);
        assertThat(AiPromptStepTask.itemTag("i0123456789abcdef", asked("Patient reports dizziness."), "wounds/a.png")).isNotEqualTo(same);
        assertThat(same).startsWith("i0123456789abcdef-").hasSize(26);
        assertThat(AiPromptStepTask.itemTag("3", Collections.singletonMap("a", "b"), null)).as("a row of no item keeps its number").isEqualTo("3");
    }
}
