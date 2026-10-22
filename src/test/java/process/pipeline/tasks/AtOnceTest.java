package process.pipeline.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static process.pipeline.Definitions.config;

/**
 * MIG-418 "Calls at once": a step's calls a few at a time, answers in row order, no workspace over its cap, a busy service
 * backed off, a failure stopping new calls; 1 is one after another on the step's own thread.
 */
class AtOnceTest {

    @BeforeEach
    void quick() {
        AtOnce.secondMillis = 1;
        AtOnce.workspaceCap(8);
    }

    @AfterEach
    void back() {
        AtOnce.secondMillis = 1000;
        AtOnce.workspaceCap(8);
    }

    @Test
    void theSettingIsOneByDefaultAndCappedAtEight() {
        assertThat(AtOnce.of(config())).isEqualTo(1);
        assertThat(AtOnce.of(config("callsAtOnce", 4))).isEqualTo(4);
        assertThat(AtOnce.of(config("callsAtOnce", 99))).isEqualTo(AtOnce.MAX_PER_STEP);
        assertThat(AtOnce.of(config("callsAtOnce", 0))).isEqualTo(1);
    }

    @Test
    void answersComeBackInRowOrderWhateverOrderTheCallsFinishIn() throws Exception {
        Random random = new Random(7);
        List<Integer> delays = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            delays.add(random.nextInt(15));
        }
        List<AtOnce.Outcome<String>> out = AtOnce.run(4101L, 40, 6, i -> {
            Thread.sleep(delays.get(i));
            return "row-" + i;
        });
        for (int i = 0; i < 40; i++) {
            assertThat(out.get(i).value).isEqualTo("row-" + i);
        }
    }

    @Test
    void oneAtATimeRunsOnTheStepsOwnThreadAndStopsAtTheFirstFailure() throws Exception {
        Thread step = Thread.currentThread();
        List<Integer> made = Collections.synchronizedList(new ArrayList<>());
        List<AtOnce.Outcome<Integer>> out = AtOnce.run(4101L, 5, 1, i -> {
            assertThat(Thread.currentThread()).isSameAs(step);
            made.add(i);
            if (i == 2) {
                throw new IllegalStateException("row 3 refused");
            }
            return i;
        });
        assertThat(made).containsExactly(0, 1, 2);
        assertThat(out.get(2).error).hasMessage("row 3 refused");
        assertThat(out.get(3).made).isFalse();
    }

    @Test
    void noWorkspaceEverHasMoreCallsInFlightThanItsCapAcrossSteps() throws Exception {
        AtOnce.workspaceCap(3);
        AtomicInteger now = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        AtomicInteger otherNow = new AtomicInteger();
        AtomicInteger otherMost = new AtomicInteger();
        ExecutorService steps = Executors.newFixedThreadPool(3);
        CountDownLatch done = new CountDownLatch(3);
        for (int s = 0; s < 3; s++) {
            long tenant = s < 2 ? 4101L : 4102L;
            AtomicInteger counter = s < 2 ? now : otherNow;
            AtomicInteger peak = s < 2 ? most : otherMost;
            steps.submit(() -> {
                try {
                    AtOnce.run(tenant, 30, 8, i -> {
                        peak.accumulateAndGet(counter.incrementAndGet(), Math::max);
                        Thread.sleep(3);
                        counter.decrementAndGet();
                        return i;
                    });
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
                return null;
            });
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        steps.shutdown();
        assertThat(most.get()).as("two steps of workspace 4101 together").isLessThanOrEqualTo(3).isGreaterThan(1);
        assertThat(otherMost.get()).as("another workspace has its own permits").isLessThanOrEqualTo(3).isGreaterThan(1);
        assertThat(AtOnce.idle(4101L, 1000)).isTrue();
    }

    @Test
    void aBusyAnswerIsMadeAgainAfterItsWaitAndHalvesTheStepsCalls() throws Exception {
        AtomicInteger tries = new AtomicInteger();
        List<AtOnce.Outcome<String>> out = AtOnce.run(4101L, 6, 4, i -> {
            if (i == 1 && tries.incrementAndGet() < 3) {
                throw new AtOnce.Busy("HTTP 429", 1);
            }
            return "ok-" + i;
        });
        assertThat(tries.get()).isEqualTo(3);
        assertThat(out).extracting(o -> o.value).containsExactly("ok-0", "ok-1", "ok-2", "ok-3", "ok-4", "ok-5");
    }

    @Test
    void stillBusyAfterItsTriesIsTheRowsFailure() throws Exception {
        List<AtOnce.Outcome<String>> out = AtOnce.run(4101L, 3, 2, i -> {
            if (i == 0) {
                throw new AtOnce.Busy("HTTP 503", 0);
            }
            return "ok";
        });
        assertThat(out.get(0).error).hasMessageContaining("HTTP 503").hasMessageContaining("still busy after 4 tries");
    }

    @Test
    void aFailureStopsNewCallsFromStarting() throws Exception {
        AtomicInteger made = new AtomicInteger();
        List<AtOnce.Outcome<Integer>> out = AtOnce.run(4101L, 200, 2, i -> {
            made.incrementAndGet();
            if (i == 3) {
                throw new IllegalStateException("refused");
            }
            Thread.sleep(2);
            return i;
        });
        assertThat(made.get()).isLessThan(20);
        assertThat(out.get(3).error).hasMessage("refused");
        assertThat(out.get(199).made).isFalse();
    }
}
