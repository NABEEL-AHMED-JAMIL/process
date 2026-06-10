package process.util;

/**
 * Generic enum parsing utilities used across the project.
 */
public class EnumUtils {

    /**
     * Parse a string into an enum value of the given type.
     * Tries the following in order:
     *  - direct valueOf(trimmed)
     *  - PascalCase conversion (e.g., "DELETE" -> "Delete")
     *  - case-insensitive match against enum names
     * @param enumType the enum class
     * @param value the input string
     * @param <T> enum type
     * @return parsed enum value or null if input null/empty
     * @throws IllegalArgumentException if no matching enum constant found
     */
    public static <T extends Enum<T>> T parseEnum(Class<T> enumType, String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();

        // direct match
        try {
            return Enum.valueOf(enumType, trimmed);
        } catch (IllegalArgumentException ignored) {
        }

        // PascalCase attempt
        String pascal = toPascalCase(trimmed);
        try {
            return Enum.valueOf(enumType, pascal);
        } catch (IllegalArgumentException ignored) {
        }

        // case-insensitive match
        for (T c : enumType.getEnumConstants()) {
            if (c.name().equalsIgnoreCase(trimmed) || c.toString().equalsIgnoreCase(trimmed)) {
                return c;
            }
        }

        throw new IllegalArgumentException("Invalid enum value for " + enumType.getSimpleName() + ": '" + value + "'");
    }

    private static String toPascalCase(String v) {
        if (v == null || v.trim().isEmpty()) return v;
        String lower = v.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}

