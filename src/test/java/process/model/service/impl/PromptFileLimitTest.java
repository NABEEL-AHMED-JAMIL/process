package process.model.service.impl;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How much of a file each provider is allowed to see.
 *
 * @author Nabeel Ahmed
 */
public class PromptFileLimitTest {

    private int limitFor(String provider) throws Exception {
        Method m = FileChatServiceImpl.class
            .getDeclaredMethod("promptFileCharsFor", String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, provider);
    }

    @Test
    void hostedModelsGetFarMoreThanTheOldSingleValue() throws Exception {
        // The old global was 30,000 -- about 7,500 tokens -- against context windows of 128k
        // to 200k. Every hosted provider should now clear it comfortably.
        for (String provider : new String[] { "ANTHROPIC", "OPENAI", "AZURE-OPENAI" }) {
            assertTrue(limitFor(provider) > 30000,
                provider + " should read more than the old 30,000 ceiling");
        }
    }

    @Test
    void aLocalModelGetsLessBecauseItsWindowIsSmaller() throws Exception {
        // The number was wrong in both directions from one value: a local build with an 8k
        // window would choke on what Claude reads without noticing.
        assertTrue(limitFor("OLLAMA") < limitFor("ANTHROPIC"));
    }

    @Test
    void anUnknownProviderGetsTheSmallestLimit() throws Exception {
        // Erring small truncates a long file; erring large overruns the window and the request
        // fails outright. Truncating is the recoverable one, and it is now visible in the UI.
        int smallest = limitFor("OLLAMA");
        assertEquals(smallest, limitFor("something-else"));
        assertEquals(smallest, limitFor(null));
        assertEquals(smallest, limitFor(""));
    }

    @Test
    void theProviderNameIsMatchedRegardlessOfCaseOrPadding() throws Exception {
        // The value arrives from a lookup row somebody typed, so it will not always be tidy.
        int expected = limitFor("ANTHROPIC");
        assertEquals(expected, limitFor("anthropic"));
        assertEquals(expected, limitFor("  Anthropic  "));
    }

    /**
     * The actual defect: the AI_PROVIDER lookup row is spelt "AzureOpenAI" (no punctuation,
     * matching how the seed data and the frontend catalogue both write it), which upper-cases to
     * "AZUREOPENAI". The map key here used to be written "AZURE-OPENAI" -- a hyphen nothing ever
     * produces -- so every Azure OpenAI agent silently got Ollama's 24k local-model limit instead
     * of the 250k a hosted model actually supports, truncating far more of a large file than
     * necessary. Both spellings are asserted equal here because both should reach the same
     * answer regardless of which punctuation a lookup row happens to carry.
     */
    @Test
    void azureOpenAiIsMatchedByItsRealLookupSpellingNotOnlyAHyphenatedOne() throws Exception {
        int azure = limitFor("AzureOpenAI");
        assertTrue(azure > 30000, "AzureOpenAI must clear the old 30,000 ceiling like every other hosted provider");
        assertEquals(limitFor("OPENAI"), azure, "Azure OpenAI is configured to share OpenAI's own limit");
        assertEquals(azure, limitFor("AZURE-OPENAI"), "punctuation must not change which provider this resolves to");
        assertEquals(azure, limitFor("azure openai"), "spacing must not change which provider this resolves to");
    }
}
