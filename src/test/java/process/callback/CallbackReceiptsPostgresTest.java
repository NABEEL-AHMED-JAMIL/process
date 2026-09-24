package process.callback;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import process.ScratchPostgres;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * worker_callback_receipt against a real Postgres (V80): the claim semantics WorkerCallbackIdempotencyTest
 * assumes of its stand-in, and the one thing a stand-in cannot show -- two deliveries racing.
 *
 * Opt-in: runs when NOTIFICATIONS_TEST_DB_URL points at a Postgres server (see ScratchPostgres).
 */
class CallbackReceiptsPostgresTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 24, 12, 0);

    private static ScratchPostgres db;
    private JdbcTemplate sql;
    private TransactionTemplate transactions;
    private JdbcCallbackReceipts receipts;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("callback_receipts");
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void empty() {
        this.sql = db.jdbc();
        this.transactions = db.transactions();
        this.receipts = new JdbcCallbackReceipts(this.sql);
        this.sql.update("DELETE FROM worker_callback_receipt");
    }

    @Test
    void theFirstClaimIsTheCallersAndARepeatSeesItsRecordedAnswer() {
        this.transactions.execute(status -> {
            assertThat(this.receipts.claim(5705L, "derived:Failed#1", "changeState:Failed", NOW)).isEmpty();
            this.receipts.record(5705L, "derived:Failed#1", null, "Job 2410 status changed to Failed");
            return null;
        });

        Optional<CallbackReceipts.Receipt> again = this.transactions.execute(status ->
            this.receipts.claim(5705L, "derived:Failed#1", "changeState:Failed", NOW.plusSeconds(3)));

        assertThat(again).isPresent();
        assertThat(again.get().request).isEqualTo("changeState:Failed");
        assertThat(again.get().outcomeMessage).isEqualTo("Job 2410 status changed to Failed");
        assertThat(this.sql.queryForObject("SELECT count(*) FROM worker_callback_receipt", Integer.class)).isEqualTo(1);
    }

    /** A key is per run: the same key on another run is another callback. */
    @Test
    void aKeyIsScopedToItsRun() {
        this.transactions.execute(status -> this.receipts.claim(5705L, "line-0000042", "addLogs", NOW));

        Optional<CallbackReceipts.Receipt> otherRun = this.transactions.execute(status ->
            this.receipts.claim(5706L, "line-0000042", "addLogs", NOW));
        assertThat(otherRun).isEmpty();
    }

    /**
     * Two deliveries of one callback, both in flight. The second insert is held by the database until
     * the first transaction commits, then reads the answer the first recorded -- it never proceeds on
     * its own, which is what two emails for one failure used to be.
     */
    @Test
    void aRaceIsDecidedByTheDatabaseAndTheLoserGetsTheWinnersAnswer() throws Exception {
        CountDownLatch firstHasClaimed = new CountDownLatch(1);
        CountDownLatch letFirstCommit = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<CallbackReceipts.Receipt>> first = threads.submit(() -> this.transactions.execute(status -> {
                Optional<CallbackReceipts.Receipt> claim = this.receipts.claim(5705L, "done-7f3a9c2e", "changeState:Completed", NOW);
                firstHasClaimed.countDown();
                try {
                    letFirstCommit.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                this.receipts.record(5705L, "done-7f3a9c2e", null, "Job 2410 status changed to Completed");
                return claim;
            }));
            assertThat(firstHasClaimed.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Optional<CallbackReceipts.Receipt>> second = threads.submit(() -> this.transactions.execute(status ->
                this.receipts.claim(5705L, "done-7f3a9c2e", "changeState:Completed", NOW)));
            Thread.sleep(300);
            assertThat(second.isDone()).as("the second delivery waits on the first").isFalse();

            letFirstCommit.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isEmpty();
            Optional<CallbackReceipts.Receipt> loser = second.get(10, TimeUnit.SECONDS);
            assertThat(loser).isPresent();
            assertThat(loser.get().outcomeMessage).isEqualTo("Job 2410 status changed to Completed");
        } finally {
            threads.shutdownNow();
        }
    }

    /** A first delivery that fails part-way rolls its claim back with its writes: the retry is a first. */
    @Test
    void aRolledBackClaimLeavesTheKeyFree() {
        this.transactions.execute(status -> {
            this.receipts.claim(5705L, "done-7f3a9c2e", "changeState:Completed", NOW);
            status.setRollbackOnly();
            return null;
        });

        Optional<CallbackReceipts.Receipt> retry = this.transactions.execute(status ->
            this.receipts.claim(5705L, "done-7f3a9c2e", "changeState:Completed", NOW));
        assertThat(retry).isEmpty();
    }

    @Test
    void thePurgeTakesOnlyReceiptsOlderThanTheCutoff() {
        this.receipts.claim(1L, "old-key-0001", "addLogs", NOW.minusDays(8));
        this.receipts.claim(2L, "new-key-0001", "addLogs", NOW.minusDays(6));

        int purged = this.receipts.purgeReceivedBefore(NOW.minusDays(CallbackReceipts.RETENTION_DAYS));

        assertThat(purged).isEqualTo(1);
        assertThat(this.receipts.find(2L, "new-key-0001")).isPresent();
        assertThat(this.receipts.find(1L, "old-key-0001")).isEmpty();
    }
}
