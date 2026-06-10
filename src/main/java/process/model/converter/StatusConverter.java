package process.model.converter;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import process.model.enums.Status;

/**
 * JPA AttributeConverter to safely convert database string values to the
 * Status enum and back. This handles case and common formatting differences
 * (for example DB stores "Delete" but Java enum reads/writes the constant name).
 */
@Converter(autoApply = true)
public class StatusConverter implements AttributeConverter<Status, String> {

    @Override
    public String convertToDatabaseColumn(Status attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public Status convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.trim().isEmpty()) {
            return null;
        }
        String trimmed = dbData.trim();

        // Try direct match first (enum names are case-sensitive)
        try {
            return Status.valueOf(trimmed);
        } catch (IllegalArgumentException ignored) {
            // Continue to next attempts
        }

        // Try PascalCase (e.g., "DELETE" -> "Delete")
        String pascal = toPascalCase(trimmed);
        try {
            return Status.valueOf(pascal);
        } catch (IllegalArgumentException ignored) {
            // Continue to fallback
        }

        // Fallback: case-insensitive match against enum names or toString
        for (Status s : Status.values()) {
            if (s.name().equalsIgnoreCase(trimmed) || s.toString().equalsIgnoreCase(trimmed)) {
                return s;
            }
        }

        // If nothing matches, throw a helpful exception
        throw new IllegalArgumentException("Invalid Status value from DB: '" + dbData + "'");
    }

    private String toPascalCase(String v) {
        if (v == null || v.trim().isEmpty()) {
            return v;
        }
        String lower = v.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}

