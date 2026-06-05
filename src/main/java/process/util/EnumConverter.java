package process.util;

import process.model.enums.Execution;
import process.model.enums.JobStatus;
import process.model.enums.Status;

/**
 * Utility class for safe enum conversion with case handling
 * Converts database values (typically uppercase) to enum values (PascalCase)
 * @author Nabeel Ahmed
 */
public class EnumConverter {

    /**
     * Convert a string value to Status enum safely
     * Handles case-insensitive conversion from database values
     * @param value Database value (e.g., "ACTIVE", "active", "Active")
     * @return Status enum value or null if value is null/invalid
     */
    public static Status toStatus(String value) {
        if (ProcessUtil.isNull(value) || value.trim().isEmpty()) {
            return null;
        }
        try {
            String normalized = normalizeToPascalCase(value);
            return Status.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Status value: " + value, e);
        }
    }

    /**
     * Convert a string value to JobStatus enum safely
     * Handles case-insensitive conversion from database values
     * @param value Database value (e.g., "QUEUE", "queue", "Queue")
     * @return JobStatus enum value or null if value is null/invalid
     */
    public static JobStatus toJobStatus(String value) {
        if (ProcessUtil.isNull(value) || value.trim().isEmpty()) {
            return null;
        }
        try {
            String normalized = normalizeForJobStatus(value);
            return JobStatus.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid JobStatus value: " + value, e);
        }
    }

    /**
     * Convert a string value to Execution enum safely
     * Handles case-insensitive conversion from database values
     * @param value Database value (e.g., "AUTO", "auto", "Auto")
     * @return Execution enum value or null if value is null/invalid
     */
    public static Execution toExecution(String value) {
        if (ProcessUtil.isNull(value) || value.trim().isEmpty()) {
            return null;
        }
        try {
            String normalized = normalizeToPascalCase(value);
            return Execution.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Execution value: " + value, e);
        }
    }

    /**
     * Normalize any case string to PascalCase
     * Converts: "ACTIVE" -> "Active", "active" -> "Active", "AcTiVe" -> "Active"
     * @param value Input string
     * @return String in PascalCase format
     */
    private static String normalizeToPascalCase(String value) {
        if (ProcessUtil.isNull(value) || value.trim().isEmpty()) {
            return value;
        }
        String trimmed = value.trim().toLowerCase();
        return trimmed.substring(0, 1).toUpperCase() + trimmed.substring(1);
    }

    /**
     * Special handling for JobStatus enum values with underscores
     * Converts: "START" -> "Start", "RUNNING" -> "Running"
     * Handles enum values like: Queue, Start, Running, Failed, Completed, Skip, Interrupt
     * @param value Input string
     * @return String matching JobStatus enum format
     */
    private static String normalizeForJobStatus(String value) {
        if (ProcessUtil.isNull(value) || value.trim().isEmpty()) {
            return value;
        }
        String trimmed = value.trim();
        // Convert to PascalCase
        String lowerCase = trimmed.toLowerCase();
        return lowerCase.substring(0, 1).toUpperCase() + lowerCase.substring(1);
    }

    /**
     * Get database-safe enum value representation (uppercase)
     * Used for storing to database
     * @param status Status enum value
     * @return Uppercase string representation
     */
    public static String getDatabaseValue(Status status) {
        return status != null ? status.toString().toUpperCase() : null;
    }

    /**
     * Get database-safe enum value representation (uppercase)
     * Used for storing to database
     * @param jobStatus JobStatus enum value
     * @return Uppercase string representation
     */
    public static String getDatabaseValue(JobStatus jobStatus) {
        return jobStatus != null ? jobStatus.toString().toUpperCase() : null;
    }

    /**
     * Get database-safe enum value representation (uppercase)
     * Used for storing to database
     * @param execution Execution enum value
     * @return Uppercase string representation
     */
    public static String getDatabaseValue(Execution execution) {
        return execution != null ? execution.toString().toUpperCase() : null;
    }
}

