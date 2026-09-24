package process.time;

import org.junit.jupiter.api.Test;
import process.ScratchJpa;
import process.ScratchPostgres;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-163: stage and prod start with spring.jpa.hibernate.ddl-auto=validate, which refuses to boot when an entity's
 * column type disagrees with the table's. After V100 every time column process maps is timestamptz; the entities
 * still say java.sql.Timestamp and LocalDateTime (through BusinessWallClockConverter). Validation has to accept that,
 * or the first deploy after V100 does not start.
 *
 * Hibernate stops at the first mismatch, so one that is nothing to do with time would hide every time column behind
 * it. Each such one is patched in this scratch database only and recorded, so validation goes on to the time
 * columns; a mismatch on a time column fails the test outright. The recorded list must be empty: anything in it
 * stops a validate-mode boot on a database built from the changelog. (There was one, scheduler.day_of_month,
 * smallint in the V50 baseline where the entity says Integer; V70.6 widens it.)
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchPostgres).
 */
class HibernateValidatesTimestamptzPostgresTest {

    private static final Pattern WRONG_TYPE = Pattern.compile(
        "wrong column type encountered in column \\[(\\w+)\\] in table \\[(\\w+)\\]; found \\[(\\w+) [^\\]]*\\], but expecting \\[(\\w+) ");

    @Test
    void theEntitiesValidateAgainstAnEtlJobBuiltThroughV100() throws Exception {
        List<String> notAboutTime = new ArrayList<>();
        try (ScratchPostgres db = ScratchPostgres.create("validate_timestamptz")) {
            for (int attempt = 0; attempt < 20; attempt++) {
                try {
                    new ScratchJpa(db.pool(), Collections.singletonMap("hibernate.hbm2ddl.auto", "validate")).close();
                    break;
                } catch (Exception refused) {
                    String message = String.valueOf(rootMessage(refused));
                    Matcher m = WRONG_TYPE.matcher(message);
                    assertThat(m.find()).as("validation refused for another reason: %s", message).isTrue();
                    String found = m.group(3);
                    assertThat(found).as("a time column failed validation: %s", message).doesNotStartWith("timestamp");
                    notAboutTime.add(m.group(2) + "." + m.group(1) + " " + found + " -> " + m.group(4));
                    db.jdbc().execute(String.format("ALTER TABLE %s ALTER COLUMN %s TYPE %s", m.group(2), m.group(1), m.group(4)));
                }
            }
        }
        assertThat(notAboutTime).as("non-time columns the changelog and the entities disagree on").isEmpty();
    }

    private static String rootMessage(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
