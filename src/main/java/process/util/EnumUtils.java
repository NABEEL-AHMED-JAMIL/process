package process.util;

public class EnumUtils {

    public static <T extends Enum<T>> T parseEnum(Class<T> enumType, String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();

        try {
            return Enum.valueOf(enumType, trimmed);
        } catch (IllegalArgumentException ignored) {
        }

        String pascal = toPascalCase(trimmed);
        try {
            return Enum.valueOf(enumType, pascal);
        } catch (IllegalArgumentException ignored) {
        }

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
