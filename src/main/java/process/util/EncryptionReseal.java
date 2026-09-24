package process.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * MIG-5: re-seals every stored secret under the current key, at startup, whenever one is configured.
 * A value still in the old untagged format is opened with the old key and sealed again, tagged; one
 * already tagged is left alone, so every later start changes nothing and replicas starting together
 * each find the rows locked or done. Once a start reports nothing left under the old key, the old key
 * (LOOKUP_ENCRYPTION_KEY) is removed from the environment, and what leaked with it opens nothing.
 *
 * A value that will not open is counted and named by its row, never shown, and the run goes on: one
 * bad row must not stop the service, and it cannot be re-sealed by anyone who cannot open it.
 */
@Component
public class EncryptionReseal implements ApplicationRunner {

    /** Every column process seals, with the row's id and which rows are sealed at all. */
    static final class Column {

        final String table;
        final String id;
        final String column;
        final String sealedRows;

        Column(String table, String id, String column, String sealedRows) {
            this.table = table;
            this.id = id;
            this.column = column;
            this.sealedRows = sealedRows;
        }

        String name() {
            return this.table + "." + this.column;
        }
    }

    static final List<Column> SEALED = Arrays.asList(
        new Column("lookup_data", "lookup_id", "lookup_value", "is_encrypted = true"),
        new Column("kafka_connection_profile", "kafka_connection_profile_id", "sasl_password", "true"),
        new Column("kafka_connection_profile", "kafka_connection_profile_id", "ssl_keystore_password_enc", "true"),
        new Column("kafka_connection_profile", "kafka_connection_profile_id", "ssl_key_password_enc", "true"),
        new Column("kafka_connection_profile", "kafka_connection_profile_id", "ssl_truststore_password_enc", "true"));

    /** What a run did, in counts only. */
    static final class Report {

        int resealed;
        int current;
        int unreadable;

        @Override
        public String toString() {
            return String.format("%d re-sealed under the current key, %d already under it, %d unreadable",
                this.resealed, this.current, this.unreadable);
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(EncryptionReseal.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EncryptionUtil encryption;

    public EncryptionReseal(JdbcTemplate jdbc, PlatformTransactionManager transactions, EncryptionUtil encryption) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.encryption = encryption;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!this.encryption.hasCurrentKey()) {
            return;
        }
        try {
            Report report = this.reseal();
            if (report.unreadable > 0) {
                logger.error("Encryption re-seal: {}. The unreadable rows are named above; they must be entered again.", report);
            } else {
                logger.info("Encryption re-seal: {}", report);
            }
        } catch (RuntimeException failed) {
            // The service still runs: every value it can open, it opens with either key.
            logger.error("Encryption re-seal did not finish, and changed nothing: {}", failed.getMessage());
        }
    }

    Report reseal() {
        return this.tx.execute(status -> {
            Report report = new Report();
            for (Column column : SEALED) {
                this.reseal(column, report);
            }
            return report;
        });
    }

    private void reseal(Column column, Report report) {
        List<Map<String, Object>> rows = this.jdbc.queryForList(String.format(
            "SELECT %s AS id, %s AS sealed FROM %s WHERE %s AND %s IS NOT NULL AND %s <> '' ORDER BY %s FOR UPDATE",
            column.id, column.column, column.table, column.sealedRows, column.column, column.column, column.id));
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            String sealed = (String) row.get("sealed");
            if (this.encryption.isCurrent(sealed)) {
                report.current++;
                continue;
            }
            String plain;
            try {
                plain = this.encryption.decrypt(sealed);
            } catch (IllegalStateException unreadable) {
                report.unreadable++;
                logger.error("Encryption re-seal: {} of row {} opens with no key process holds", column.name(), id);
                continue;
            }
            this.jdbc.update(String.format("UPDATE %s SET %s = ? WHERE %s = ?", column.table, column.column, column.id),
                this.encryption.encrypt(plain), id);
            report.resealed++;
        }
    }
}
