package process.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** MIG-74: what the redaction keeps -- the shape -- and what it must never let through. */
class SqlLogRedactionTest {

    @Test
    void literalsBecomePlaceholdersAndIdentifiersStay() {
        assertThat(SqlLogRedaction.redact("select jq.job_queue_id from job_queue jq where jq.job_id in (7, 12) "
            + "and sj.tenant_id = 2901 and date(jq.date_created) between '2026-09-01' and '2026-09-21' limit 50"))
            .isEqualTo("select jq.job_queue_id from job_queue jq where jq.job_id in (?, ?) "
                + "and sj.tenant_id = ? and date(jq.date_created) between ? and ? limit ?");
    }

    @Test
    void aQuoteInsideASearchTermDoesNotEndTheLiteralEarly() {
        assertThat(SqlLogRedaction.redact("and upper(st.task_name) like upper('%O''Brien payroll%') and st.tenant_id = 2901"))
            .isEqualTo("and upper(st.task_name) like upper(?) and st.tenant_id = ?");
    }

    @Test
    void anUnpairedQuoteHidesEverythingAfterIt() {
        assertThat(SqlLogRedaction.redact("where name = 'secret payroll and st.tenant_id = 2901"))
            .isEqualTo("where name = ?");
    }
}
