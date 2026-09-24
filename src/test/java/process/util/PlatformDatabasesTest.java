package process.util;

import org.junit.jupiter.api.Test;
import process.util.validation.SourceTaskValidation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform's own databases hold configuration, never a pipeline's data: a task that names one
 * as the database it reads or writes is refused (owner's rule, 2026-09-24 -- demo_orders_import and
 * two more tables had been loaded into etl_job by the CSV -> Postgres test pipeline).
 */
class PlatformDatabasesTest {

    private static String task(String body) {
        return "<?xml version=\"1.0\"?><pipeline><bucket>etl-bucket</bucket>" + body + "</pipeline>";
    }

    @Test
    void aTaskNamingAPlatformDatabaseIsRefusedAndTheRefusalNamesIt() {
        assertThat(PlatformDatabases.refusal(task("<db_name>etl_job</db_name><target_table>t</target_table>")))
            .hasValueSatisfying(message -> assertThat(message).contains("etl_job").contains("configuration"));
        for (String db : new String[] {"notifications_db", "media_db", "storage_db", "billing_db", "analytics_db", "ai_db", "identity_db"}) {
            assertThat(PlatformDatabases.refusal(task("<db_name>" + db + "</db_name>"))).as(db).isPresent();
        }
    }

    @Test
    void spacingCaseAndAJdbcUrlDoNotSlipPast() {
        assertThat(PlatformDatabases.refusal(task("<db_name>  ETL_JOB \n</db_name>"))).isPresent();
        assertThat(PlatformDatabases.refusal(task("<jdbc_url>jdbc:postgresql://host.docker.internal:5433/etl_job?sslmode=disable</jdbc_url>"))).isPresent();
        assertThat(PlatformDatabases.refusal(task("<database>etl_job</database>"))).isPresent();
    }

    @Test
    void aDatabaseOfThePipelinesOwnIsFine() {
        assertThat(PlatformDatabases.refusal(task("<db_name>etl_data</db_name><target_table>orders</target_table>"))).isEmpty();
        assertThat(PlatformDatabases.refusal(task("<db_name>etl_job_archive</db_name>"))).as("a name that only starts alike").isEmpty();
        assertThat(PlatformDatabases.refusal(task("<target_table>etl_job</target_table>"))).as("a table named so is not the database").isEmpty();
        assertThat(PlatformDatabases.refusal(null)).isEmpty();
    }

    /** The bulk upload applies the same rule, row by row. */
    @Test
    void aBulkUploadRowNamingAPlatformDatabaseIsAnError() {
        SourceTaskValidation row = new SourceTaskValidation();
        row.setRowCounter(7);
        row.setSourceTaskTypeId("17");
        row.setTaskName("orders");
        row.setTaskPayload(task("<db_name>etl_job</db_name>"));
        row.isValidSourceTask();
        assertThat(row.getErrorMsg()).contains("etl_job").contains("row 7");
    }
}
