package process.forms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules of a form's fields, and of the answers to them (Wave 5 Forms lite). The console checks the same things as
 * the person types; this is the check that counts.
 *
 * <p>A field: a key (lower case letters, digits and '_', starting with a letter, at most 40 -- the answer's name and the
 * column a pipeline reads -- unique in the form and none of the names the submission file uses itself), a label, a type
 * ({@link #TYPES}), required or not, help text, and for a choice its options (1 to 50, distinct). At most 50 fields.
 *
 * <p>An answer: text at most 500 characters, long text at most 5000, a number (a JSON number or text that is one), a
 * date (yyyy-MM-dd), one of a choice's options, yes/no (true or false), an e-mail address. A required field must be
 * answered -- blank text is no answer; yes/no is answered by either. An answer to no field of the form is refused.
 */
public final class FormFields {

    public static final String TEXT = "text";
    public static final String LONG_TEXT = "longText";
    public static final String NUMBER = "number";
    public static final String DATE = "date";
    public static final String CHOICE = "choice";
    public static final String YES_NO = "yesNo";
    public static final String EMAIL = "email";
    public static final List<String> TYPES = Collections.unmodifiableList(Arrays.asList(TEXT, LONG_TEXT, NUMBER, DATE, CHOICE, YES_NO,
        EMAIL));

    /** The names the submission file gives its own values; no field may take one. */
    public static final List<String> RESERVED = Collections.unmodifiableList(Arrays.asList("submission_id", "form_id", "form_name",
        "form_version", "submitted_by", "submitted_at"));

    static final int MAX_FIELDS = 50;
    static final int MAX_OPTIONS = 50;
    static final int MAX_LABEL = 120;
    static final int MAX_HELP = 500;
    static final int MAX_TEXT = 500;
    static final int MAX_LONG_TEXT = 5000;
    static final int MAX_EMAIL = 254;
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");
    private static final Pattern EMAIL_ADDRESS = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private FormFields() {
    }

    /** A definition the rules refuse: the first thing wrong, in a sentence. */
    public static final class Refused extends IllegalArgumentException {
        public Refused(String message) {
            super(message);
        }
    }

    /** Answers the rules refuse: a sentence for each field that is wrong, by key, in the form's order. */
    public static final class Unanswered extends IllegalArgumentException {
        private final Map<String, String> problems;

        public Unanswered(Map<String, String> problems) {
            super(problems.size() == 1 ? problems.values().iterator().next()
                : String.format("%d answers need attention: %s", problems.size(), String.join(" ", problems.values())));
            this.problems = Collections.unmodifiableMap(new LinkedHashMap<>(problems));
        }

        public Map<String, String> getProblems() {
            return this.problems;
        }
    }

    // ---- the definition ----------------------------------------------------------------------------------------

    /** The fields as they are kept: trimmed, blank help and a non-choice's options dropped. Refused when any is wrong. */
    public static List<FormField> valid(List<FormField> fields) {
        if (fields == null) {
            return new ArrayList<>();
        }
        if (fields.size() > MAX_FIELDS) {
            throw new Refused(String.format("A form has at most %d fields; this one has %d.", MAX_FIELDS, fields.size()));
        }
        List<FormField> kept = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int position = 0;
        for (FormField field : fields) {
            position++;
            if (field == null) {
                throw new Refused(String.format("Field %d is empty.", position));
            }
            String label = trimmed(field.getLabel());
            String name = label == null ? String.format("Field %d", position) : String.format("Field %d (%s)", position, label);
            String key = trimmed(field.getKey());
            if (key == null || !KEY.matcher(key).matches()) {
                throw new Refused(name + ": its key must start with a lower-case letter and use only a-z, 0-9 and _ (at most 40).");
            }
            if (RESERVED.contains(key)) {
                throw new Refused(name + ": the key '" + key + "' is used by the submission itself; choose another.");
            }
            if (!keys.add(key)) {
                throw new Refused(name + ": another field already has the key '" + key + "'.");
            }
            if (label == null) {
                throw new Refused(name + ": give it a label.");
            }
            if (label.length() > MAX_LABEL) {
                throw new Refused(String.format("%s: its label is longer than %d characters.", name, MAX_LABEL));
            }
            String type = trimmed(field.getType());
            if (type == null || !TYPES.contains(type)) {
                throw new Refused(String.format("%s: its type must be one of %s.", name, String.join(", ", TYPES)));
            }
            String help = trimmed(field.getHelp());
            if (help != null && help.length() > MAX_HELP) {
                throw new Refused(String.format("%s: its help text is longer than %d characters.", name, MAX_HELP));
            }
            List<String> options = null;
            if (CHOICE.equals(type)) {
                options = options(name, field.getOptions());
            }
            kept.add(new FormField(key, label, type, field.isRequired(), help, options));
        }
        return kept;
    }

    private static List<String> options(String name, List<String> given) {
        List<String> options = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String option : given == null ? Collections.<String>emptyList() : given) {
            String text = trimmed(option);
            if (text == null) {
                continue;
            }
            if (text.length() > MAX_LABEL) {
                throw new Refused(String.format("%s: the option '%s...' is longer than %d characters.", name, text.substring(0, 20), MAX_LABEL));
            }
            if (!seen.add(text.toLowerCase())) {
                throw new Refused(String.format("%s: the option '%s' is listed twice.", name, text));
            }
            options.add(text);
        }
        if (options.isEmpty()) {
            throw new Refused(name + ": a choice needs at least one option.");
        }
        if (options.size() > MAX_OPTIONS) {
            throw new Refused(String.format("%s: a choice has at most %d options.", name, MAX_OPTIONS));
        }
        return options;
    }

    // ---- the answers -------------------------------------------------------------------------------------------

    /**
     * The answers as they are kept, in the form's order: text trimmed, numbers as numbers, dates as yyyy-MM-dd, a choice
     * as its option's own spelling; an unanswered optional field is left out. Unanswered, with every problem, otherwise.
     */
    public static Map<String, Object> answers(List<FormField> fields, Map<String, Object> given) {
        Map<String, Object> answers = given == null ? Collections.<String, Object>emptyMap() : given;
        Map<String, String> problems = new LinkedHashMap<>();
        Map<String, Object> kept = new LinkedHashMap<>();
        Set<String> known = new HashSet<>();
        for (FormField field : fields) {
            known.add(field.getKey());
            Object value = answers.get(field.getKey());
            if (isBlank(value)) {
                if (field.isRequired()) {
                    problems.put(field.getKey(), field.getLabel() + " is required.");
                }
                continue;
            }
            try {
                kept.put(field.getKey(), answer(field, value));
            } catch (IllegalArgumentException wrong) {
                problems.put(field.getKey(), field.getLabel() + ": " + wrong.getMessage());
            }
        }
        for (String key : answers.keySet()) {
            if (!known.contains(key)) {
                problems.put(key, "'" + key + "' is not a field of this form.");
            }
        }
        if (!problems.isEmpty()) {
            throw new Unanswered(problems);
        }
        return kept;
    }

    private static Object answer(FormField field, Object value) {
        switch (field.getType()) {
            case TEXT:
                return text(value, MAX_TEXT);
            case LONG_TEXT:
                return text(value, MAX_LONG_TEXT);
            case EMAIL: {
                String email = text(value, MAX_EMAIL);
                if (!EMAIL_ADDRESS.matcher(email).matches()) {
                    throw new IllegalArgumentException("enter an e-mail address, like name@example.com.");
                }
                return email;
            }
            case NUMBER:
                return number(value);
            case DATE:
                return date(value);
            case CHOICE: {
                String chosen = text(value, MAX_LABEL);
                for (String option : field.getOptions() == null ? Collections.<String>emptyList() : field.getOptions()) {
                    if (option.equalsIgnoreCase(chosen)) {
                        return option;
                    }
                }
                throw new IllegalArgumentException("choose one of " + String.join(", ", field.getOptions()) + ".");
            }
            case YES_NO:
                if (value instanceof Boolean) {
                    return value;
                }
                if (value instanceof String && ("true".equalsIgnoreCase(((String) value).trim()) || "false".equalsIgnoreCase(((String) value).trim()))) {
                    return Boolean.valueOf(((String) value).trim());
                }
                throw new IllegalArgumentException("answer yes or no.");
            default:
                throw new IllegalArgumentException("this field's type is unknown.");
        }
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String) && !(value instanceof Number)) {
            throw new IllegalArgumentException("enter text.");
        }
        String text = String.valueOf(value).trim();
        if (text.length() > max) {
            throw new IllegalArgumentException(String.format("at most %d characters (this is %d).", max, text.length()));
        }
        return text;
    }

    private static Object number(Object value) {
        BigDecimal number;
        if (value instanceof Number) {
            number = new BigDecimal(value.toString());
        } else if (value instanceof String) {
            try {
                number = new BigDecimal(((String) value).trim());
            } catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException("enter a number.");
            }
        } else {
            throw new IllegalArgumentException("enter a number.");
        }
        if (number.abs().compareTo(new BigDecimal("1e15")) >= 0 || number.scale() > 6) {
            throw new IllegalArgumentException("enter a number below 10^15, with at most 6 decimals.");
        }
        BigDecimal plain = number.stripTrailingZeros();
        return plain.scale() <= 0 ? (Object) plain.longValueExact() : (Object) plain;
    }

    private static String date(Object value) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("enter a date as yyyy-mm-dd.");
        }
        try {
            return LocalDate.parse(((String) value).trim()).toString();
        } catch (DateTimeParseException notADate) {
            throw new IllegalArgumentException("enter a date as yyyy-mm-dd.");
        }
    }

    private static boolean isBlank(Object value) {
        return value == null || (value instanceof String && ((String) value).trim().isEmpty());
    }

    static String trimmed(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
