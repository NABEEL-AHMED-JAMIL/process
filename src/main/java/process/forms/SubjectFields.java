package process.forms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ZT-I34 (owner 2026-10-27): what each key of a form submission's request subject is, for workflow-service -- the form's
 * fields in the form's own order ({key, label, type, section, personal}), then the keys the submission adds itself (form,
 * submission_id, submitted_by). The request shows its answers as the form asks them, and masks the personal ones for
 * people who may not see personal data. JSON keeps no order, so the order is this list's.
 */
public final class SubjectFields {

    private SubjectFields() {
    }

    /**
     * The subject's fields as the submission answered them ({@code answered}, its version's), each personal when that version
     * or the form as it is now ({@code current}) marks it so: a field marked personal later is masked in earlier requests too.
     */
    public static List<Map<String, Object>> of(List<FormField> answered, List<FormField> current) {
        Set<String> personalNow = new HashSet<>();
        for (FormField field : current == null ? Collections.<FormField>emptyList() : current) {
            if (field != null && Boolean.TRUE.equals(field.getPersonal())) {
                personalNow.add(field.getKey());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (FormField field : answered == null ? Collections.<FormField>emptyList() : answered) {
            if (field == null || field.getKey() == null) {
                continue;
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("key", field.getKey());
            f.put("label", field.getLabel());
            f.put("type", field.getType());
            if (field.getSection() != null) {
                f.put("section", field.getSection());
            }
            if (Boolean.TRUE.equals(field.getPersonal()) || personalNow.contains(field.getKey())) {
                f.put("personal", true);
            }
            out.add(f);
        }
        out.add(own("form", "Form", "text"));
        out.add(own("submission_id", "Submission", "number"));
        out.add(own("submitted_by", "Submitted by", "text"));
        return out;
    }

    private static Map<String, Object> own(String key, String label, String type) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("label", label);
        f.put("type", type);
        return f;
    }
}
