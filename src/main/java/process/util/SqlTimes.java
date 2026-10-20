package process.util;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Date;

/**
 * MIG-389: a timestamp cell of a native query, as the java.sql.Timestamp Hibernate 5 handed back. Hibernate 6+ answers a
 * timestamptz column as an Instant (and other drivers' types as OffsetDateTime); the code that read
 * those cells cast them to Timestamp. The same instant either way.
 *
 * @author Nabeel Ahmed
 */
public final class SqlTimes {

    private SqlTimes() {
    }

    public static Timestamp timestamp(Object cell) {
        if (cell == null || cell instanceof Timestamp) {
            return (Timestamp) cell;
        }
        if (cell instanceof Instant) {
            return Timestamp.from((Instant) cell);
        }
        if (cell instanceof OffsetDateTime) {
            return Timestamp.from(((OffsetDateTime) cell).toInstant());
        }
        if (cell instanceof ZonedDateTime) {
            return Timestamp.from(((ZonedDateTime) cell).toInstant());
        }
        if (cell instanceof Date) {
            return new Timestamp(((Date) cell).getTime());
        }
        throw new ClassCastException("Not a timestamp: " + cell.getClass().getName());
    }
}
