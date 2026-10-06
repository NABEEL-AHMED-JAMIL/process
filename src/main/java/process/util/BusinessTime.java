package process.util;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The business's clock: America/Chicago wall-clock, named where it is meant (MIG-163, ADR-002).
 *
 * The database holds instants (V100). What the business means by "09:00 daily", "today", "the hour a run
 * started in" and what both consoles show is Chicago wall-clock, carried in LocalDateTime. This class is the
 * one place the two meet, so nothing in process reads the JVM's default zone -- UTC in the containers, and
 * nobody's business -- and nothing has to be told what it is.
 *
 * DST, decided once, here, and the same way Postgres's AT TIME ZONE and the old Chicago JVM decide it (V100's
 * backfill agrees with every value the application writes afterwards):
 * - a wall-clock time in the spring-forward gap (2026-03-08 02:30) is moved on by the gap: 03:30 CDT;
 * - a wall-clock time in the fall-back hour (2026-11-01 01:30, which happens twice) is the SECOND one, CST.
 *   java.time's default is the first; asked for explicitly below.
 */
public final class BusinessTime {

    /** The zone the business runs on. */
    public static final ZoneId ZONE = ZoneId.of("America/Chicago");

    private static volatile Clock clock = Clock.system(ZONE);

    private BusinessTime() {
    }

    /** Chicago wall-clock now: what LocalDateTime.now() returned while the JVM was told it lived in Chicago. */
    public static LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** Today in Chicago. */
    public static LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The instant a Chicago wall-clock reading means; see the class comment for the two DST edges. */
    public static Instant instantOf(LocalDateTime wallClock) {
        if (wallClock == null) {
            return null;
        }
        return ZonedDateTime.ofLocal(wallClock, ZONE, null).withLaterOffsetAtOverlap().toInstant();
    }

    /** As instantOf, for a JDBC parameter compared with (or written to) a timestamptz column. */
    public static Timestamp timestampOf(LocalDateTime wallClock) {
        return wallClock == null ? null : Timestamp.from(instantOf(wallClock));
    }

    /** The Chicago wall-clock reading of an instant. */
    public static LocalDateTime wallClockOf(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZONE);
    }

    /** The Chicago wall-clock reading of a Timestamp read from a timestamptz column (an instant, whatever the JVM's zone). */
    public static LocalDateTime wallClockOf(Timestamp timestamp) {
        return timestamp == null ? null : wallClockOf(timestamp.toInstant());
    }

    /** For a native query's row, whose temporal cells arrive as whatever the driver made of them. */
    public static LocalDateTime wallClockOf(Object cell) {
        if (cell == null) {
            return null;
        }
        if (cell instanceof Timestamp) {
            return wallClockOf((Timestamp) cell);
        }
        if (cell instanceof Instant) {
            return wallClockOf((Instant) cell);
        }
        if (cell instanceof OffsetDateTime) {
            return wallClockOf(((OffsetDateTime) cell).toInstant());
        }
        if (cell instanceof LocalDateTime) {
            return (LocalDateTime) cell;
        }
        throw new IllegalArgumentException("Not a time: " + cell.getClass().getName());
    }

    /**
     * An instant printed as java.sql.Timestamp#toString printed it on the Chicago JVM -- "2026-01-16 09:00:00.0" --
     * for the few places that text reached a user or a console. Timestamp#toString itself now prints the JVM's
     * zone.
     */
    public static String legacyText(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        LocalDateTime wall = wallClockOf(timestamp);
        String nanos;
        if (wall.getNano() == 0) {
            nanos = "0";
        } else {
            nanos = String.format("%09d", wall.getNano()).replaceAll("0+$", "");
        }
        return String.format("%04d-%02d-%02d %02d:%02d:%02d.%s", wall.getYear(), wall.getMonthValue(), wall.getDayOfMonth(),
            wall.getHour(), wall.getMinute(), wall.getSecond(), nanos);
    }

    /** Tests only: read "now" from this clock instead of the system's. Its zone is ignored; the business zone is used. */
    public static void useClock(Clock testClock) {
        clock = testClock.withZone(ZONE);
    }

    /** Tests only: back to the system clock. */
    public static void useSystemClock() {
        clock = Clock.system(ZONE);
    }
}
