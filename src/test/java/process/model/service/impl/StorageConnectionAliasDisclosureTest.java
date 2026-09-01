package process.model.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Storage aliases are unique across the whole platform, so the uniqueness check has to look at
 * rows the caller cannot otherwise see. What it must not do is describe them: a tenant admin
 * posting candidate aliases one at a time would otherwise learn which ones other tenants have
 * taken, and an alias is exactly what the storage endpoints are aimed with.
 *
 * @author Nabeel Ahmed
 */
public class StorageConnectionAliasDisclosureTest {

    @Test
    void theRejectionNeverRepeatsTheAliasBack() {
        assertFalse(StorageConnectionServiceImpl.ALIAS_UNAVAILABLE.contains("%s"),
            "a message that formats the alias in confirms which guess landed");
    }

    @Test
    void theRejectionDoesNotConfirmThatAConnectionExists() {
        String message = StorageConnectionServiceImpl.ALIAS_UNAVAILABLE.toLowerCase();
        assertFalse(message.contains("already exists"));
        assertFalse(message.contains("another storage connection"));
        assertFalse(message.contains("different storage connection"));
    }

    @Test
    void theRejectionStillTellsTheCallerWhatToDo() {
        assertTrue(StorageConnectionServiceImpl.ALIAS_UNAVAILABLE.toLowerCase().contains("alias"),
            "non-committal is not the same as unhelpful");
    }

}
