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
}
