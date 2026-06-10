package process.util;

import process.model.enums.Execution;
import process.model.enums.JobStatus;
import process.model.enums.Status;

/**
 * Deprecated compatibility wrapper. Use {@link EnumUtils} instead.
 * This class is kept to avoid breaking code during refactor and will be removed
 * in a future release.
 * @author Nabeel Ahmed
 */
@Deprecated
public class EnumConverter {

    /**
     * Convert a string value to Status enum safely
     * Handles case-insensitive conversion from database values
     * @param value Database value (e.g., "ACTIVE", "active", "Active")
     * @return Status enum value or null if value is null/invalid
     */
    public static Status toStatus(String value) {
        return EnumUtils.parseEnum(Status.class, value);
    }

    /**
     * Convert a string value to JobStatus enum safely
     * Handles case-insensitive conversion from database values
     * @param value Database value (e.g., "QUEUE", "queue", "Queue")
     * @return JobStatus enum value or null if value is null/invalid
     */
    public static JobStatus toJobStatus(String value) {
        return EnumUtils.parseEnum(JobStatus.class, value);
    }

    /**
     * Convert a string value to Execution enum safely
     * Handles case-insensitive conversion from database values
     * @param value Database value (e.g., "AUTO", "auto", "Auto")
     * @return Execution enum value or null if value is null/invalid
     */
    public static Execution toExecution(String value) {
        return EnumUtils.parseEnum(Execution.class, value);
    }

    // Helper methods were refactored to `EnumUtils` and JPA converters are used
    // to control database representations (see process.model.converter package).
}

