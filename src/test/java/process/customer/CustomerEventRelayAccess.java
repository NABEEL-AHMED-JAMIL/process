package process.customer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import process.forms.FormStore;
import process.outbox.OutboxWriter;
import process.pipeline.StepStore;
import process.pipeline.review.RunReviews;

/** For tests outside this package (the Postgres probes): the relay on a transaction template of their own pool. */
public final class CustomerEventRelayAccess {

    private CustomerEventRelayAccess() {
    }

    public static CustomerEventRelay relay(JdbcTemplate jdbc, TransactionTemplate transactions, OutboxWriter outbox, CustomerRuns runs,
        RunReviews reviews, StepStore steps, FormStore forms) {
        return new CustomerEventRelay(jdbc, transactions, outbox, runs, reviews, steps, forms);
    }
}
