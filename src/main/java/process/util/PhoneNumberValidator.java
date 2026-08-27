package process.util;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

/**
 * Checks a phone number against the rules of the country that issued it.
 *
 * A length check is not enough and a single regex is worse: national number lengths differ by
 * country and by line type, some countries have several valid lengths, and a number can be the
 * right length while its prefix belongs to no operator. libphonenumber carries Google's
 * metadata for every country, so "does this exist" is answered from the same data phones use.
 *
 * Numbers are held in E.164 -- a leading + then country code then national number, digits only.
 * That form is unambiguous, sorts and compares as a string, and needs no second column for the
 * country: the dialling code is a prefix, so the country is always recoverable.
 *
 * @author Nabeel Ahmed
 */
public final class PhoneNumberValidator {

    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

    private PhoneNumberValidator() {}

    /**
     * The number in E.164, or a message saying why it was rejected.
     *
     * Blank is accepted and normalises to null: a phone number is optional, and an empty box
     * should clear the field rather than fail the whole save.
     */
    public static Result normalise(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Result.ok(null);
        }
        String candidate = raw.trim();
        if (!candidate.startsWith("+")) {
            // Without a leading + there is no country, and guessing one silently files a number
            // under the wrong country -- which only shows up when somebody tries to ring it.
            return Result.error("Include the country code, starting with + (for example +12025550143).");
        }
        try {
            Phonenumber.PhoneNumber parsed = UTIL.parse(candidate, null);
            if (!UTIL.isValidNumber(parsed)) {
                String region = UTIL.getRegionCodeForNumber(parsed);
                return Result.error(region == null
                    ? "That country code is not one we recognise."
                    : String.format("That is not a valid %s number -- check the digits after the country code.", region));
            }
            return Result.ok(UTIL.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164));
        } catch (NumberParseException ex) {
            return Result.error("That does not look like a phone number.");
        }
    }

    /** Which country a stored number belongs to, for showing a flag beside it. Null if unknown. */
    public static String regionOf(String e164) {
        if (e164 == null || e164.trim().isEmpty()) {
            return null;
        }
        try {
            return UTIL.getRegionCodeForNumber(UTIL.parse(e164.trim(), null));
        } catch (NumberParseException ex) {
            return null;
        }
    }

    public static final class Result {

        private final String value;
        private final String error;

        private Result(String value, String error) {
            this.value = value;
            this.error = error;
        }

        static Result ok(String value) {
            return new Result(value, null);
        }

        static Result error(String error) {
            return new Result(null, error);
        }

        public boolean isValid() {
            return this.error == null;
        }

        public String getValue() {
            return this.value;
        }

        public String getError() {
            return this.error;
        }
    }
}
