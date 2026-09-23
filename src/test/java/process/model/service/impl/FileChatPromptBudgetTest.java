package process.model.service.impl;

import org.junit.jupiter.api.Test;
import process.media.MediaPort;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FileChat's side of the extraction ceiling (formerly half of ExtractionCapTest, split when Media
 * left for media-service in MIG-48): whatever the per-provider prompt budgets are set to, they have
 * to sit at or under the ceiling Media promises, or extraction becomes the limit that decides what
 * the model reads while the limit that decides what the user is TOLD never fires. Media pins the
 * same number on its side.
 */
class FileChatPromptBudgetTest {

    @Test
    void everyProviderPromptBudgetFitsUnderTheExtractionCeiling() throws Exception {
        Field budgetsField = FileChatServiceImpl.class.getDeclaredField("PROMPT_FILE_CHARS_BY_PROVIDER");
        budgetsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Integer> budgets = (Map<String, Integer>) budgetsField.get(null);

        assertThat(MediaPort.EXTRACTION_CEILING_CHARS)
            .as("extraction must be the backstop against a pathological file, never the thing "
                + "that routinely and silently decides what the model reads")
            .isGreaterThanOrEqualTo(Collections.max(budgets.values()));
    }
}
