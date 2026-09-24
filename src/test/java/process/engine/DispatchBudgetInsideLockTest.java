package process.engine;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import process.engine.cron.ProcessCron;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant I1 (MIG-156): one dispatch pass must end before the lock that makes it the only one does.
 *
 * DispatchTiming.DISPATCH_BUDGET_MS (ProducerBulkEngine's until MIG-136) stops startJobInCurrentTimeSlot's loop; the @SchedulerLock on
 * ProcessCron.startJobInCurrentTimeSlot is what keeps a second instance from running the same pass.
 * Verbatim from the constant: "The @SchedulerLock around it is ten minutes, and the work has to finish
 * inside that or a second instance can claim the same rows." If the budget ever reaches the lock, two
 * instances dispatch the same queue rows -- double dispatch, the very thing getCountForInQueueJobByJobId
 * exists to prevent.
 *
 * Two constants in two classes that agree today by nobody's design, so the test asserts the
 * RELATIONSHIP and reads both: hardcoding 7 and 10 separately would not notice either one moving. The
 * lock's text is read through ShedLock's own parser, so "10M" means here what it means at runtime.
 */
class DispatchBudgetInsideLockTest {

    private static Duration shedLockDuration(String text) throws Exception {
        Class<?> type = Class.forName("net.javacrumbs.shedlock.spring.aop.StringToDurationConverter");
        Field instance = type.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        @SuppressWarnings("unchecked")
        Converter<String, Duration> converter = (Converter<String, Duration>) instance.get(null);
        return converter.convert(text);
    }

    /** Read from DispatchTiming, where the four coupled dispatch values are written down together (MIG-136). */
    private static Duration dispatchBudget() {
        return Duration.ofMillis(DispatchTiming.DISPATCH_BUDGET_MS);
    }

    @Test
    void theDispatchBudgetEndsStrictlyInsideTheDispatchLock() throws Exception {
        SchedulerLock lock = ProcessCron.class.getMethod("startJobInCurrentTimeSlot").getAnnotation(SchedulerLock.class);
        Duration lockAtMostFor = shedLockDuration(lock.lockAtMostFor());
        Duration budget = dispatchBudget();

        assertThat(budget)
            .as("DISPATCH_BUDGET_MS (%s) must stay below lockAtMostFor (%s) on %s, or a second instance "
                + "can claim the rows this pass is still dispatching", budget, lockAtMostFor, lock.name())
            .isLessThan(lockAtMostFor);
    }

    /** The lock the budget is measured against is the dispatcher's own, not the enqueue cron's. */
    @Test
    void theLockMeasuredIsTheDispatchersOwn() throws Exception {
        SchedulerLock lock = ProcessCron.class.getMethod("startJobInCurrentTimeSlot").getAnnotation(SchedulerLock.class);
        assertThat(lock.name()).isEqualTo("startJobInCurrentTimeSlot");
        assertThat(shedLockDuration(lock.lockAtMostFor())).isPositive();
    }
}
