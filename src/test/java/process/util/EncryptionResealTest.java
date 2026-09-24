package process.util;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** MIG-5: with no current key configured there is nothing to re-seal to, so a start touches nothing. */
class EncryptionResealTest {

    @Test
    void withoutACurrentKeyAStartTouchesNoRow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

        new EncryptionReseal(jdbc, transactions, new EncryptionUtil()).run(null);

        verifyNoInteractions(jdbc, transactions);
    }
}
