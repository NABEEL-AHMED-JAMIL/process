package process.model.converter;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import process.model.enums.Status;

/**
 * @author Nabeel Ahmed
 * */
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

        try {
            return Status.valueOf(trimmed);
        } catch (IllegalArgumentException ignored) {

        }

        String pascal = toPascalCase(trimmed);
        try {
            return Status.valueOf(pascal);
        } catch (IllegalArgumentException ignored) {

        }

        for (Status s : Status.values()) {
            if (s.name().equalsIgnoreCase(trimmed) || s.toString().equalsIgnoreCase(trimmed)) {
                return s;
            }
        }

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
