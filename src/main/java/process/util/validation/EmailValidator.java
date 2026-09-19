package process.util.validation;

import java.util.regex.Pattern;

/**
 * One rule for what counts as an e-mail address, wherever the console takes one: a workspace
 * request, a share, a billing profile. Deliberately loose -- something before an @, a domain
 * with a dot after it, no whitespace -- because the mail server is the judge and a stricter
 * pattern only ever rejects real addresses.
 */
public final class EmailValidator {

    private static final Pattern ADDRESS = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private EmailValidator() {}

    public static boolean isValid(String email) {
        return email != null && ADDRESS.matcher(email.trim()).matches();
    }
}
