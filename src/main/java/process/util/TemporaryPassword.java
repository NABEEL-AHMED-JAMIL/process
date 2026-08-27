package process.util;

import java.security.SecureRandom;

/**
 * One-time passwords for accounts somebody else creates.
 *
 * Shared rather than copied so both places that hand out a first password -- an approved
 * workspace request and an administrator adding a user -- produce the same strength and the
 * same alphabet. A generated value is emailed to its owner and is never logged, never returned
 * in a response, and never shown to whoever created the account.
 *
 * @author Nabeel Ahmed
 * */
public final class TemporaryPassword {

    private static final int LENGTH = 16;

    /**
     * No I, l, 1, O or 0. The password is read off a screen and typed by hand, and a character
     * someone mistypes is indistinguishable from a wrong password.
     */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPassword() {
    }

    public static String generate() {
        StringBuilder out = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }
}
