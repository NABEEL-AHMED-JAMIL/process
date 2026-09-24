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
 * Hibernate stops at the first mismatch, and a changelog-built etl_job already has some that are nothing to do with
 * time (scheduler.day_of_month is smallint in the V50 baseline, the entity says Integer). Each such one is patched
 * in this scratch database only and recorded, so validation goes on to the time columns; a mismatch on a time column
 * fails the test outright. The recorded list is pinned, so it is visible, and so it is noticed when it changes.
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
        // Pre-existing, and not this change's: each would stop a validate-mode boot on a database built from the changelog.
        assertThat(notAboutTime).containsExactly("scheduler.day_of_month int2 -> int4");
    }

    private static String rootMessage(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
