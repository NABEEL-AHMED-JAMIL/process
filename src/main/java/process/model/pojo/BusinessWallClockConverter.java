package process.model.pojo;

import process.util.BusinessTime;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Every LocalDateTime attribute is America/Chicago wall-clock, stored as the instant it means (MIG-163, V100).
 *
 * Hibernate on its own binds a LocalDateTime through Timestamp.valueOf -- the JVM's default zone. That was right
 * only while ModelApplication told the JVM it lived in Chicago; the containers are UTC. This names the zone
 * instead, and resolves the two DST edges the way V100's backfill did (BusinessTime), so a value the application
 * writes and a value V100 converted mean the same thing. Applied to every LocalDateTime attribute of every entity.
 */
@Converter(autoApply = true)
public class BusinessWallClockConverter implements AttributeConverter<LocalDateTime, Timestamp> {

    @Override
    public Timestamp convertToDatabaseColumn(LocalDateTime wallClock) {
        return BusinessTime.timestampOf(wallClock);
    }

    @Override
    public LocalDateTime convertToEntityAttribute(Timestamp instant) {
        return BusinessTime.wallClockOf(instant);
    }
}
