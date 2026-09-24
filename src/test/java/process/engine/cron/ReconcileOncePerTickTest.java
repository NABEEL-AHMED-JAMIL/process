package process.engine.cron;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronSequenceGenerator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7 (MIG-132): with two replicas, reconcileStalledRuns runs once per tick, not twice.
 *
 * It ran twice. The sweep was scheduled by fixedDelay, which counts from each JVM's own start, so two
 * replicas fired at their own moments -- as far apart as their boot times, i.e. anything. The ShedLock
 * on it held for lockAtLeastFor = 5 s, so it only merged two firings that happened to land within five
 * seconds of each other; two replicas started a minute apart each swept every fifteen minutes. A lock
 * serialises; it only deduplicates what fires at the same time. The two-instance harness showed it
 * (ops/two-instance, T7): replica B started 15 s after A, and the sweep ran on both.
 *
 * So the sweep is scheduled by the clock (a cron), which every replica reads the same, and the lock
 * is held long enough to cover the clock skew between them. This test replays the scheduling itself:
 * two replicas booted apart, with a skewed clock, through ShedLock's rules -- one execution per tick.
 */
class ReconcileOncePerTickTest {

    private static final Duration PROD_TICK = Duration.ofMinutes(15);

    /** How far apart two replicas' clocks may be and still be one tick: NTP keeps them far closer. */
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(20);

    private static Method sweep() throws Exception {
        return ProcessCron.class.getMethod("reconcileStalledRuns");
    }

    private static Duration shedLockDuration(String text) throws Exception {
        Class<?> type = Class.forName("net.javacrumbs.shedlock.spring.aop.StringToDurationConverter");
        Field instance = type.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        @SuppressWarnings("unchecked")
        Converter<String, Duration> converter = (Converter<String, Duration>) instance.get(null);
        return converter.convert(text);
    }

    /** The default a "${name:default}" placeholder falls back to, which is what production runs. */
    private static String defaultOf(String placeholder) {
        if (placeholder.startsWith("${") && placeholder.endsWith("}")) {
            return placeholder.substring(placeholder.indexOf(':') + 1, placeholder.length() - 1);
        }
        return placeholder;
    }

    /**
     * When one replica would fire, from its boot to the horizon, reading the schedule the way Spring
     * does: a cron from the replica's (skewed) clock, a fixedDelay from the replica's own start.
     */
    private static List<Long> firings(Scheduled scheduled, long bootMillis, long skewMillis, long horizonMillis) {
        List<Long> at = new ArrayList<>();
        if (!scheduled.cron().isEmpty()) {
            CronSequenceGenerator cron = new CronSequenceGenerator(defaultOf(scheduled.cron()), TimeZone.getTimeZone("UTC"));
            // The replica reads its own clock, which is the true time plus its skew.
            Date next = cron.next(new Date(bootMillis + skewMillis));
            while (next.getTime() - skewMillis < horizonMillis) {
                at.add(next.getTime() - skewMillis);
                next = cron.next(next);
            }
        } else {
            long delay = scheduled.fixedDelay() >= 0 ? scheduled.fixedDelay() : scheduled.fixedRate();
            long t = bootMillis + Math.max(0, scheduled.initialDelay());
            while (t < horizonMillis) {
                at.add(t);
                t += delay;
            }
        }
        return at;
    }

    /** ShedLock's rule: a firing runs when the lock is free, and holds it for lockAtLeastFor. */
    private static List<Long> executions(List<Long> firings, Duration lockAtLeastFor) {
        List<Long> sorted = new ArrayList<>(firings);
        sorted.sort(Long::compare);
        List<Long> ran = new ArrayList<>();
        long lockedUntil = Long.MIN_VALUE;
        for (long t : sorted) {
            if (t >= lockedUntil) {
                ran.add(t);
                lockedUntil = t + lockAtLeastFor.toMillis();
            }
        }
        return ran;
    }

    @Test
    void theSweepIsScheduledByTheClockSoEveryReplicaFiresAtTheSameTick() throws Exception {
        Scheduled scheduled = sweep().getAnnotation(Scheduled.class);
        assertThat(scheduled.cron())
            .as("a fixedDelay counts from each JVM's own start, so replicas fire at unrelated moments")
            .isNotEmpty();
        assertThat(scheduled.fixedDelay()).isNegative();
        assertThat(scheduled.fixedRate()).isNegative();
        assertThat(scheduled.fixedDelayString()).isEmpty();
        assertThat(scheduled.fixedRateString()).isEmpty();
    }

    @Test
    void productionTicksEveryFifteenMinutesAsBefore() throws Exception {
        Scheduled scheduled = sweep().getAnnotation(Scheduled.class);
        CronSequenceGenerator cron = new CronSequenceGenerator(defaultOf(scheduled.cron()), TimeZone.getTimeZone("UTC"));
        Date first = cron.next(new Date(0));
        Date second = cron.next(first);
        assertThat(Duration.ofMillis(second.getTime() - first.getTime())).isEqualTo(PROD_TICK);
    }

    @Test
    void theLockCoversClockSkewAndEndsBeforeTheNextTick() throws Exception {
        SchedulerLock lock = sweep().getAnnotation(SchedulerLock.class);
        Duration atLeast = shedLockDuration(lock.lockAtLeastFor());
        Duration atMost = shedLockDuration(lock.lockAtMostFor());
        assertThat(atLeast).as("lockAtLeastFor must outlast the skew between replicas' clocks").isGreaterThan(CLOCK_SKEW);
        assertThat(atLeast).as("lockAtLeastFor must end before the harness's one-minute tick, let alone production's")
            .isLessThan(Duration.ofMinutes(1));
        assertThat(atMost).isGreaterThanOrEqualTo(atLeast);
        assertThat(atMost).as("a dead holder's lock must be back before the next production tick").isLessThan(PROD_TICK);
    }

    @Test
    void twoReplicasBootedApartSweepOncePerTick() throws Exception {
        Scheduled scheduled = sweep().getAnnotation(Scheduled.class);
        Duration atLeast = shedLockDuration(sweep().getAnnotation(SchedulerLock.class).lockAtLeastFor());
        long horizon = Duration.ofHours(4).toMillis();
        // Replica A boots at 00:00:07; B at 00:01:44 (a rolling deploy), with its clock 20 s fast.
        List<Long> all = new ArrayList<>(firings(scheduled, 7_000L, 0L, horizon));
        all.addAll(firings(scheduled, 104_000L, CLOCK_SKEW.toMillis(), horizon));

        List<Long> ran = executions(all, atLeast);

        // One per production tick in four hours, give or take the tick in progress when B arrived.
        assertThat(ran).hasSizeBetween(15, 16);
        for (int i = 1; i < ran.size(); i++) {
            assertThat(Duration.ofMillis(ran.get(i) - ran.get(i - 1)))
                .as("two sweeps %s apart are one tick run twice", Duration.ofMillis(ran.get(i) - ran.get(i - 1)))
                .isGreaterThan(PROD_TICK.minus(CLOCK_SKEW.multipliedBy(2)));
        }
    }
}
