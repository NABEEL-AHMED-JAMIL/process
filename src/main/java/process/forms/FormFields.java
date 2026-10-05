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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The rules of a form's fields, and of the answers to them (Wave 5 Forms lite; MIG-277 adds tables, files, signatures,
 * lookups and conditions). The console checks the same things as the person types; this is the check that counts.
 *
 * <p>A field: a key (lower case letters, digits and '_', starting with a letter, at most 40 -- the answer's name and the
 * column a pipeline reads -- unique in the form and none of the names the submission file uses itself), a label, a type
 * ({@link #TYPES}), required or not, help text, and for a choice its options (1 to 50, distinct). At most 50 fields.
 *
 * <p>An answer: text at most 500 characters, long text at most 5000, a number (a JSON number or text that is one), a
 * date (yyyy-MM-dd), one of a choice's options, yes/no (true or false), an e-mail address. A required field must be
 * answered -- blank text is no answer; yes/no is answered by either. An answer to no field of the form is refused.
 *
 * <p>MIG-277. A table: 1 to 20 columns (each a field of a plain type, no rules of its own) and up to maxRows rows (1 to
 * 200, 20 when not said); a blank row is no row, a required table needs one. A file: up to maxFiles files (1 to 10) of
 * the accepted extensions ({@link #ALLOWED_EXTENSIONS}), each at most maxSizeMb (1 to 25, 10 when not said), uploaded
 * first by the same person to the same form's field and sent once. A signature: one drawn PNG, uploaded the same way.
 * A lookup: one of the values another form of the workspace collected for one of its fields.
 *
 * <p>showWhen and requiredWhen name an EARLIER field (so a form reads top to bottom and a rule never loops) and an
 * operator: eq, ne, in, gt, lt, filled, empty. A field that is not shown is not asked: an answer sent for it anyway is
 * dropped, not refused (the page may have held it before the person changed their mind).
 */
public final class FormFields {

    public static final String TEXT = "text";
    public static final String LONG_TEXT = "longText";
    public static final String NUMBER = "number";
    public static final String DATE = "date";
    public static final String CHOICE = "choice";
    public static final String YES_NO = "yesNo";
    public static final String EMAIL = "email";
    public static final String TABLE = "table";
    public static final String FILE = "file";
    public static final String SIGNATURE = "signature";
    public static final String LOOKUP = "lookup";
    public static final List<String> TYPES = Collections.unmodifiableList(Arrays.asList(TEXT, LONG_TEXT, NUMBER, DATE, CHOICE, YES_NO,
        EMAIL, TABLE, FILE, SIGNATURE, LOOKUP));
    /** The types a table's column may have. */
    public static final List<String> COLUMN_TYPES = Collections.unmodifiableList(Arrays.asList(TEXT, NUMBER, DATE, CHOICE, YES_NO, EMAIL));
    /** The types whose answer is an upload. */
    public static final List<String> UPLOAD_TYPES = Collections.unmodifiableList(Arrays.asList(FILE, SIGNATURE));
    public static final List<String> OPERATORS = Collections.unmodifiableList(Arrays.asList("eq", "ne", "in", "gt", "lt", "filled", "empty"));
    /** What a file field may accept: documents, images and data -- nothing a browser or a desktop would run. */
    public static final List<String> ALLOWED_EXTENSIONS = Collections.unmodifiableList(Arrays.asList("pdf", "png", "jpg", "jpeg", "gif",
        "webp", "tif", "tiff", "heic", "csv", "txt", "json", "xml", "xlsx", "xls", "docx", "doc", "pptx", "odt", "ods", "mp3", "wav",
        "m4a", "mp4", "mov", "zip"));
    /** What a file field accepts when the builder does not say. */
    public static final List<String> DEFAULT_ACCEPT = Collections.unmodifiableList(Arrays.asList("pdf", "png", "jpg", "jpeg", "csv", "txt",
        "xlsx", "docx"));

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
    static final int MAX_COLUMNS = 20;
    static final int DEFAULT_ROWS = 20;
    static final int MAX_ROWS = 200;
    static final int DEFAULT_SIZE_MB = 10;
    static final int MAX_SIZE_MB = 25;
    static final int MAX_FILES = 10;
    /**
     * The largest file any form field takes. An upload over it is refused before its bytes are read into memory: the
     * request itself may be far larger (the multipart limit is the inbox's, 500 MB), and it waits on disk until then.
     */
    public static final long LARGEST_UPLOAD_BYTES = MAX_SIZE_MB * 1024L * 1024L;
    /** A drawn signature is a small PNG. */
    public static final long MAX_SIGNATURE_BYTES = 512L * 1024;
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

    /** A file or signature a person uploaded to a form's field, as the answer keeps it. */
    public static final class Upload {
        public final long uploadId;
        public final String name;
        public final String contentType;
        public final long size;
        public final String bucket;
        public final String key;

        public Upload(long uploadId, String name, String contentType, long size, String bucket, String key) {
            this.uploadId = uploadId;
            this.name = name;
            this.contentType = contentType;
            this.size = size;
            this.bucket = bucket;
            this.key = key;
        }

        Map<String, Object> answer() {
            Map<String, Object> kept = new LinkedHashMap<>();
            kept.put("uploadId", this.uploadId);
            kept.put("name", this.name);
            kept.put("contentType", this.contentType);
            kept.put("size", this.size);
            kept.put("bucket", this.bucket);
            kept.put("key", this.key);
            return kept;
        }
    }

    /** What answering needs from outside the form: a lookup's values now, and the person's uploads not yet sent. */
    public interface Context {
        List<String> lookupValues(FormField field);

        /** The upload, when this person uploaded it to this field of this form and no submission has taken it. */
        Optional<Upload> upload(String fieldKey, long uploadId);
    }

    /** No lookups, no uploads: what a form of plain fields needs. */
    public static final Context NONE = new Context() {
        @Override
        public List<String> lookupValues(FormField field) {
            return Collections.emptyList();
        }

        @Override
        public Optional<Upload> upload(String fieldKey, long uploadId) {
            return Optional.empty();
        }
    };

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
            FormField valid = new FormField(key, label, type, field.isRequired(), help, options);
            switch (type) {
                case TABLE:
                    valid.setColumns(columns(name, field.getColumns()));
                    valid.setMaxRows(bounded(name, "maxRows", field.getMaxRows(), 1, MAX_ROWS, DEFAULT_ROWS));
                    break;
                case FILE:
                    valid.setAccept(accept(name, field.getAccept()));
                    valid.setMaxSizeMb(bounded(name, "maxSizeMb", field.getMaxSizeMb(), 1, MAX_SIZE_MB, DEFAULT_SIZE_MB));
                    valid.setMaxFiles(bounded(name, "maxFiles", field.getMaxFiles(), 1, MAX_FILES, 1));
                    // MIG-271: kept only when on, so a form saved before it reads exactly as it did.
                    valid.setToDocuments(Boolean.TRUE.equals(field.getToDocuments()) ? Boolean.TRUE : null);
                    break;
                case LOOKUP:
                    valid.setLookup(lookup(name, field.getLookup()));
                    break;
                default:
                    break;
            }
            valid.setShowWhen(rule(name, "shown", field.getShowWhen(), kept));
            valid.setRequiredWhen(rule(name, "required", field.getRequiredWhen(), kept));
            kept.add(valid);
        }
        return kept;
    }

    private static List<FormField> columns(String name, List<FormField> given) {
        if (given == null || given.isEmpty()) {
            throw new Refused(name + ": a table needs at least one column.");
        }
        if (given.size() > MAX_COLUMNS) {
            throw new Refused(String.format("%s: a table has at most %d columns.", name, MAX_COLUMNS));
        }
        List<FormField> columns = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int position = 0;
        for (FormField column : given) {
            position++;
            String label = column == null ? null : trimmed(column.getLabel());
            String at = String.format("%s, column %d%s", name, position, label == null ? "" : " (" + label + ")");
            String key = column == null ? null : trimmed(column.getKey());
            if (key == null || !KEY.matcher(key).matches()) {
                throw new Refused(at + ": its key must start with a lower-case letter and use only a-z, 0-9 and _ (at most 40).");
            }
            if (!keys.add(key)) {
                throw new Refused(at + ": another column already has the key '" + key + "'.");
            }
            if (label == null || label.length() > MAX_LABEL) {
                throw new Refused(at + ": give it a label of at most " + MAX_LABEL + " characters.");
            }
            String type = trimmed(column.getType());
            if (type == null || !COLUMN_TYPES.contains(type)) {
                throw new Refused(String.format("%s: a column's type must be one of %s.", at, String.join(", ", COLUMN_TYPES)));
            }
            columns.add(new FormField(key, label, type, column.isRequired(), null, CHOICE.equals(type) ? options(at, column.getOptions()) : null));
        }
        return columns;
    }

    private static List<String> accept(String name, List<String> given) {
        if (given == null || given.isEmpty()) {
            return new ArrayList<>(DEFAULT_ACCEPT);
        }
        List<String> accept = new ArrayList<>();
        for (String extension : given) {
            String plain = extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
            if (plain.isEmpty()) {
                continue;
            }
            if (!ALLOWED_EXTENSIONS.contains(plain)) {
                throw new Refused(String.format("%s: '%s' files cannot be accepted; choose from %s.", name, plain,
                    String.join(", ", ALLOWED_EXTENSIONS)));
            }
            if (!accept.contains(plain)) {
                accept.add(plain);
            }
        }
        return accept.isEmpty() ? new ArrayList<>(DEFAULT_ACCEPT) : accept;
    }

    private static int bounded(String name, String what, Integer given, int min, int max, int otherwise) {
        if (given == null) {
            return otherwise;
        }
        if (given < min || given > max) {
            throw new Refused(String.format("%s: %s is from %d to %d.", name, what, min, max));
        }
        return given;
    }

    private static FormField.Lookup lookup(String name, FormField.Lookup given) {
        String field = given == null ? null : trimmed(given.getField());
        if (given == null || given.getFormId() == null || field == null) {
            throw new Refused(name + ": a lookup names the form and the field whose answers it offers.");
        }
        return new FormField.Lookup(given.getFormId(), field);
    }

    /** A rule on an earlier field; null when there is none. */
    private static FormField.Rule rule(String name, String what, FormField.Rule given, List<FormField> earlier) {
        if (given == null || trimmed(given.getField()) == null && trimmed(given.getOp()) == null) {
            return null;
        }
        String on = trimmed(given.getField());
        FormField target = null;
        for (FormField field : earlier) {
            if (field.getKey().equals(on)) {
                target = field;
            }
        }
        if (target == null) {
            throw new Refused(String.format("%s: it can be %s only by a field above it ('%s' is not one).", name, what, on));
        }
        String op = trimmed(given.getOp());
        if (op == null || !OPERATORS.contains(op)) {
            throw new Refused(String.format("%s: the rule's test must be one of %s.", name, String.join(", ", OPERATORS)));
        }
        boolean presence = "filled".equals(op) || "empty".equals(op);
        if (!presence && (TABLE.equals(target.getType()) || UPLOAD_TYPES.contains(target.getType()))) {
            throw new Refused(String.format("%s: a %s field can only be tested for filled or empty.", name, target.getType()));
        }
        Object value = presence ? null : given.getValue();
        if (!presence && (value == null || value instanceof String && ((String) value).trim().isEmpty())) {
            throw new Refused(String.format("%s: say what '%s' is compared with.", name, on));
        }
        if ("in".equals(op) && !(value instanceof List)) {
            throw new Refused(String.format("%s: 'in' compares with a list of values.", name));
        }
        return new FormField.Rule(on, op, value);
    }

    /** Whether a rule holds on the answers kept so far (a field not answered, or not shown, is empty). */
    public static boolean holds(FormField.Rule rule, Map<String, Object> kept) {
        Object answer = kept.get(rule.getField());
        boolean empty = isBlank(answer) || answer instanceof List && ((List<?>) answer).isEmpty();
        switch (rule.getOp()) {
            case "filled":
                return !empty;
            case "empty":
                return empty;
            case "eq":
                return !empty && same(answer, rule.getValue());
            case "ne":
                return empty || !same(answer, rule.getValue());
            case "in":
                if (empty || !(rule.getValue() instanceof List)) {
                    return false;
                }
                for (Object option : (List<?>) rule.getValue()) {
                    if (same(answer, option)) {
                        return true;
                    }
                }
                return false;
            case "gt":
                return !empty && compare(answer, rule.getValue()) > 0;
            case "lt":
                return !empty && compare(answer, rule.getValue()) < 0;
            default:
                return false;
        }
    }

    private static boolean same(Object answer, Object value) {
        BigDecimal left = decimal(answer);
        BigDecimal right = decimal(value);
        if (left != null && right != null) {
            return left.compareTo(right) == 0;
        }
        return String.valueOf(answer).trim().equalsIgnoreCase(String.valueOf(value).trim());
    }

    /** Numbers as numbers, anything else (a yyyy-MM-dd date) as text; a number and a word never order (0). */
    private static int compare(Object answer, Object value) {
        BigDecimal left = decimal(answer);
        BigDecimal right = decimal(value);
        if (left != null && right != null) {
            return left.compareTo(right);
        }
        if (left != null || right != null) {
            return 0;
        }
        return String.valueOf(answer).trim().compareTo(String.valueOf(value).trim());
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        if (value instanceof String) {
            try {
                return new BigDecimal(((String) value).trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
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
        return answers(fields, given, NONE);
    }

    /** As {@link #answers(List, Map)}, with the lookups' values and the person's uploads from {@code context}. */
    public static Map<String, Object> answers(List<FormField> fields, Map<String, Object> given, Context context) {
        Map<String, Object> answers = given == null ? Collections.<String, Object>emptyMap() : given;
        Map<String, String> problems = new LinkedHashMap<>();
        Map<String, Object> kept = new LinkedHashMap<>();
        Set<String> known = new HashSet<>();
        Set<Long> taken = new HashSet<>();
        for (FormField field : fields) {
            known.add(field.getKey());
            if (field.getShowWhen() != null && !holds(field.getShowWhen(), kept)) {
                continue;
            }
            boolean required = field.isRequired() || field.getRequiredWhen() != null && holds(field.getRequiredWhen(), kept);
            Object value = answers.get(field.getKey());
            try {
                Object answer = isBlank(value) ? null : answer(field, value, context, taken);
                if (answer == null || answer instanceof List && ((List<?>) answer).isEmpty()) {
                    if (required) {
                        problems.put(field.getKey(), field.getLabel() + " is required.");
                    }
                    continue;
                }
                kept.put(field.getKey(), answer);
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

    private static Object answer(FormField field, Object value, Context context, Set<Long> taken) {
        switch (field.getType()) {
            case TABLE:
                return rows(field, value);
            case FILE: {
                List<Object> ids = value instanceof List ? new ArrayList<>((List<?>) value) : new ArrayList<>(Collections.singletonList(value));
                ids.removeIf(FormFields::isBlank);
                int most = field.getMaxFiles() == null ? 1 : field.getMaxFiles();
                if (ids.size() > most) {
                    throw new IllegalArgumentException(most == 1 ? "attach one file." : String.format("attach at most %d files.", most));
                }
                List<Map<String, Object>> files = new ArrayList<>();
                for (Object id : ids) {
                    files.add(uploaded(field, id, context, taken).answer());
                }
                return files;
            }
            case SIGNATURE: {
                Upload signature = uploaded(field, value, context, taken);
                if (!"image/png".equals(signature.contentType)) {
                    throw new IllegalArgumentException("sign in the box again.");
                }
                return signature.answer();
            }
            case LOOKUP: {
                String chosen = text(value, MAX_TEXT);
                for (String option : context.lookupValues(field)) {
                    if (option.equalsIgnoreCase(chosen)) {
                        return option;
                    }
                }
                throw new IllegalArgumentException("choose one of the listed values.");
            }
            default:
                return scalar(field, value);
        }
    }

    /** A table's rows: blank rows dropped, each cell checked as its column's type. */
    private static List<Map<String, Object>> rows(FormField table, Object value) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("send its rows as a list.");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int number = 0;
        for (Object row : (List<?>) value) {
            number++;
            if (row != null && !(row instanceof Map)) {
                throw new IllegalArgumentException(String.format("row %d is not a row.", number));
            }
            Map<?, ?> cells = row == null ? Collections.emptyMap() : (Map<?, ?>) row;
            if (cells.values().stream().allMatch(FormFields::isBlank)) {
                continue;
            }
            Map<String, Object> kept = new LinkedHashMap<>();
            Set<String> columnKeys = new HashSet<>();
            for (FormField column : table.getColumns()) {
                columnKeys.add(column.getKey());
                Object cell = cells.get(column.getKey());
                if (isBlank(cell)) {
                    if (column.isRequired()) {
                        problems.add(String.format("row %d, %s is required.", number, column.getLabel()));
                    }
                    continue;
                }
                try {
                    kept.put(column.getKey(), scalar(column, cell));
                } catch (IllegalArgumentException wrong) {
                    problems.add(String.format("row %d, %s: %s", number, column.getLabel(), wrong.getMessage()));
                }
            }
            for (Object key : cells.keySet()) {
                if (!columnKeys.contains(String.valueOf(key))) {
                    problems.add(String.format("row %d: '%s' is not a column.", number, key));
                }
            }
            rows.add(kept);
        }
        int most = table.getMaxRows() == null ? DEFAULT_ROWS : table.getMaxRows();
        if (rows.size() > most) {
            problems.add(String.format("at most %d rows (this has %d).", most, rows.size()));
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        return rows;
    }

    private static Upload uploaded(FormField field, Object value, Context context, Set<Long> taken) {
        Object id = value instanceof Map ? ((Map<?, ?>) value).get("uploadId") : value;
        BigDecimal number = decimal(id);
        long uploadId;
        try {
            uploadId = number == null ? -1 : number.longValueExact();
        } catch (ArithmeticException notWhole) {
            uploadId = -1;
        }
        Optional<Upload> upload = uploadId > 0 ? context.upload(field.getKey(), uploadId) : Optional.<Upload>empty();
        if (!upload.isPresent() || !taken.add(uploadId)) {
            throw new IllegalArgumentException("upload the file here again: that one is not yours to send, or was sent already.");
        }
        return upload.get();
    }

    private static Object scalar(FormField field, Object value) {
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
        return value == null || (value instanceof String && ((String) value).trim().isEmpty())
            || (value instanceof List && ((List<?>) value).isEmpty());
    }

    static String trimmed(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
