package process.util.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-167: a spreadsheet row is held to the payload-only configuration rules while it is read, with the other row
 * errors -- the same place PlatformDatabases is checked -- so every bad row is named in one answer.
 */
class SourceTaskValidationConfigRuleTest {

    @Test
    void aRowWithALiteralPasswordIsNamedAndTheValueIsNot() {
        SourceTaskValidation row = row("<pipeline><db_password>hunter2-canary</db_password></pipeline>");

        row.isValidSourceTask();

        assertThat(row.getErrorMsg()).contains("<db_password>").contains("(row 3)").doesNotContain("hunter2-canary");
    }

    @Test
    void aRowWithAMalformedReferenceIsNamed() {
        SourceTaskValidation row = row("<pipeline><bucket>${config:lower}</bucket></pipeline>");

        row.isValidSourceTask();

        assertThat(row.getErrorMsg()).contains("UPPER_SNAKE").contains("(row 3)");
    }

    @Test
    void aRowWithASecretReferenceIsValid() {
        SourceTaskValidation row = row("<pipeline><db_password>${secret:DB_PASSWORD}</db_password></pipeline>");

        row.isValidSourceTask();

        assertThat(row.getErrorMsg()).isNull();
    }

    private static SourceTaskValidation row(String payload) {
        SourceTaskValidation row = new SourceTaskValidation();
        row.setRowCounter(3);
        row.setSourceTaskTypeId("7300");
        row.setTaskName("uploaded");
        row.setTaskPayload(payload);
        return row;
    }
}
