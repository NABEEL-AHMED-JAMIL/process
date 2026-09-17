package process.model.service.impl;

import org.jodconverter.core.DocumentConverter;
import org.jodconverter.core.document.DocumentFormatRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.ObjectContentDto;
import process.model.service.AudioTranscriptService;
import process.model.service.StorageBrowserService;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Which of the two length limits is actually in charge of what the model reads.
 *
 * Extraction has its own ceiling and FileChatServiceImpl has a per-provider prompt budget, and
 * only the second one is reported: resolveContext compares what it received against the budget,
 * and that comparison is where {@code FileContext.truncated} -- the "[content truncated]" note in
 * the prompt and the warning banner in the panel -- comes from. The extraction ceiling used to be
 * 60,000, below every single provider budget, which inverted that. A 500,000-character contract
 * reached FileChatServiceImpl as exactly 60,000 characters, 60,000 compared favourably against
 * Anthropic's 400,000, and the whole stack concluded nothing had been cut while 440,000
 * characters were already gone. The model, told to ground its answers strictly in the file and
 * never to pretend the file covers something it does not, then denied content that had been
 * thrown away here -- and the reader had no banner, no note and no log line to tell them so.
 *
 * So the ordering between the two constants is the behaviour, not a tuning detail, and it is what
 * this pins.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class FileChatExtractionCapTest {

    private static final String BUCKET = "docs";
    private static final String KEY = "contract.txt";

    @Mock private StorageBrowserService storageBrowserService;
    @Mock private AudioTranscriptService audioTranscriptService;
    @Mock private DocumentConverter documentConverter;
    @Mock private DocumentFormatRegistry documentFormatRegistry;

    private FileChatExtractionServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new FileChatExtractionServiceImpl(this.storageBrowserService,
            this.audioTranscriptService, this.documentConverter, this.documentFormatRegistry);
    }

    /**
     * The direct consequence: a file that comfortably clears the old 60,000 ceiling but sits well
     * inside a hosted provider's budget has to arrive whole, so that the only component entitled
     * to decide the text was cut -- and to say so -- is the one that reports it.
     */
    @Test
    void aFileWellPastTheOldCeilingIsHandedOverWhole() throws Exception {
        String contract = filler(120000);
        when(this.storageBrowserService.downloadObject(BUCKET, KEY, null, null))
            .thenReturn(new ObjectContentDto(
                new ByteArrayInputStream(contract.getBytes(StandardCharsets.UTF_8)),
                "text/plain", contract.length(), KEY));

        String extracted = this.service.extractText(BUCKET, KEY, "etag-1");

        assertThat(extracted)
            .as("a silent cut here is invisible to every truncation check downstream")
            .hasSize(120000)
            .isEqualTo(contract);
    }

    /**
     * And the invariant behind it, stated once rather than left to be rediscovered: whatever the
     * extraction ceiling is set to, it has to sit at or above the largest per-provider budget, or
     * extraction becomes the limit that decides what the model reads while the limit that decides
     * what the user is TOLD never fires.
     */
    @Test
    void theExtractionCeilingSitsAboveEveryProviderPromptBudget() throws Exception {
        Field ceilingField = FileChatExtractionServiceImpl.class.getDeclaredField("MAX_TEXT_CHARS");
        ceilingField.setAccessible(true);
        int ceiling = ceilingField.getInt(null);

        Field budgetsField = FileChatServiceImpl.class.getDeclaredField("PROMPT_FILE_CHARS_BY_PROVIDER");
        budgetsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Integer> budgets = (Map<String, Integer>) budgetsField.get(null);

        int largestBudget = Collections.max(budgets.values());
        assertThat(ceiling)
            .as("extraction must be the backstop against a pathological file, never the thing "
                + "that routinely and silently decides what the model reads")
            .isGreaterThanOrEqualTo(largestBudget);
    }

    private static String filler(int length) {
        StringBuilder sb = new StringBuilder(length);
        while (sb.length() < length) {
            sb.append("Clause text that pads the contract out to a realistic length. ");
        }
        return sb.substring(0, length);
    }
}
