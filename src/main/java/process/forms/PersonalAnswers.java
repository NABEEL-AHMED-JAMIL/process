package process.forms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.identity.IdentityPort;
import process.security.TenantContext;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ZT-I34 (owner 2026-10-27, "mask for readers without access"): a form's answers marked personal data (FormField.personal),
 * masked in Forms > Submissions and its CSV for people who may not see personal data -- the same rule as the requests
 * workflow-service masks: a workspace administrator (our staff in a managed session) and a member holding "See personal
 * data" (identity's PageKey.SEE_PERSONAL_DATA, asked at the page gate for /personalData.json/show) see them, and so does
 * the person who sent the submission; everyone else reads the platform's mask. When Identity cannot answer, masked.
 */
@Component
public class PersonalAnswers {

    private static final Logger logger = LoggerFactory.getLogger(PersonalAnswers.class);

    /** The page gate's path for See personal data (identity's PageKey.SEE_PERSONAL_DATA owns /personalData.json). */
    public static final String PATH = "/personalData.json/show";
    /** The platform's mask (platform-commons SensitiveValues.MASK). */
    public static final String MASK = "•••";

    private IdentityPort identity;

    @Autowired(required = false)
    public void useIdentity(IdentityPort identity) {
        this.identity = identity;
    }

    /** Whether the signed-in person may see the personal answers of a submission sent by {@code submittedBy}. */
    public boolean maySee(Long submittedBy) {
        String role = TenantContext.getUserRole();
        // A workspace administrator, our staff's managed session among them (its role is the workspace's TENANT_ADMIN); never
        // the platform administrator's own sign-in.
        if ("TENANT_ADMIN".equals(role)) {
            return true;
        }
        if (!"TENANT_USER".equals(role)) {
            return false;
        }
        Long me = TenantContext.getAppUserId();
        if (me != null && me.equals(submittedBy)) {
            return true;
        }
        if (this.identity == null) {
            return false;
        }
        try {
            return this.identity.pageDecision(role, me, PATH).isAllowed();
        } catch (IdentityPort.Unavailable unavailable) {
            logger.warn("Identity could not say whether {} may see personal data: {}", me, unavailable.getMessage());
            return false;
        }
    }

    /** The keys of the fields marked personal data. */
    public static Set<String> keys(Collection<FormField> fields) {
        if (fields == null) {
            return Collections.emptySet();
        }
        return fields.stream().filter(f -> f != null && Boolean.TRUE.equals(f.getPersonal())).map(FormField::getKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The answers with each personal one the mask (an unanswered one stays unanswered). */
    public static Map<String, Object> masked(Map<String, Object> answers, Set<String> keys) {
        if (answers == null || keys.isEmpty()) {
            return answers;
        }
        Map<String, Object> out = new LinkedHashMap<>(answers);
        for (String key : keys) {
            Object value = out.get(key);
            if (value != null && !(value instanceof String && ((String) value).isBlank())) {
                out.put(key, MASK);
            }
        }
        return out;
    }

    /** A text (an approval's note kept on the submission) with the personal answers' values in it masked. */
    public static String text(String text, Map<String, Object> answers, Set<String> keys) {
        if (text == null || text.isEmpty() || answers == null || keys.isEmpty()) {
            return text;
        }
        List<String> values = keys.stream().map(answers::get).filter(v -> v instanceof String || v instanceof Number).map(String::valueOf)
            .map(String::trim).filter(v -> v.length() >= 3).distinct().sorted(Comparator.comparingInt(String::length).reversed())
            .collect(Collectors.toList());
        if (values.isEmpty()) {
            return text;
        }
        Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + values.stream().map(Pattern::quote).collect(Collectors.joining("|"))
            + ")(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return p.matcher(text).replaceAll(Matcher.quoteReplacement(MASK));
    }
}
