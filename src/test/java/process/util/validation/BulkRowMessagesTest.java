package process.util.validation;

import org.junit.jupiter.api.Test;
import process.util.ProcessUtil;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bulk upload's per-row reasons are shown as text in the console, one line per reason. They were
 * written for an old HTML page: each ended in a literal "<br>", some did not, so two reasons for one
 * row ran together ("at row 4.Issue with ..."), and they read "not valid its should be".
 */
class BulkRowMessagesTest {

    private static JobDetailValidation jobRow() {
        JobDetailValidation row = new JobDetailValidation();
        row.setRowCounter(4);
        row.setJobName("");
        row.setTaskId("1859");
        row.setStartDate("2026-10-01");
        row.setEndDate("2026-12-31");
        row.setStartTime("09:00");
        row.setFrequency("Fortnightly");
        row.setRecurrence("1");
        row.setPriority("3");
        row.setEmailJobComplete("false");
        row.setEmailJobFail("false");
        row.setEmailJobSkip("false");
        return row;
    }

    @Test
    void aJobRowsReasonsArePlainTextOnePerLine() {
        JobDetailValidation row = jobRow();

        row.isValidJobDetail();

        String message = row.getErrorMsg();
        assertThat(message).doesNotContain("<br").doesNotContain("its should be");
        assertThat(message.split("\n")).hasSizeGreaterThanOrEqualTo(2)
            .allSatisfy(line -> assertThat(line).isNotBlank().contains("row 4"));
        assertThat(message).contains("Frequency must be one of [Mint, Hr, Daily, Weekly, Monthly]");
    }

    @Test
    void aTaskRowsReasonsArePlainTextOnePerLineAndKeepTheTagsTheyName() {
        SourceTaskValidation row = new SourceTaskValidation();
        row.setRowCounter(3);
        row.setTaskName("");
        row.setTaskPayload("<pipeline><db_password>hunter2</db_password></pipeline>");

        row.isValidSourceTask();

        String message = row.getErrorMsg();
        assertThat(message).doesNotContain("<br").contains("<db_password>");
        assertThat(message.split("\n")).hasSizeGreaterThanOrEqualTo(2).allSatisfy(line -> assertThat(line).isNotBlank());
    }

    @Test
    void theSummarySaysNothingWasSaved() {
        assertThat(ProcessUtil.rejectedRowsMessage(1))
            .isEqualTo("1 row could not be imported, so nothing was saved. Fix it and upload the sheet again.");
        assertThat(ProcessUtil.rejectedRowsMessage(3)).startsWith("3 rows could not be imported");
    }
}
