package process.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The point of these is the cases a length check gets wrong.
 *
 * @author Nabeel Ahmed
 */
public class PhoneNumberValidatorTest {

    @Test
    void realNumbersFromSeveralCountriesAreAccepted() {
        for (String number : new String[] {
            "+12025550143",      // United States, 10 national digits
            "+442079460958",     // United Kingdom, London landline
            "+923001234567",     // Pakistan, 10
            "+9613456789",       // Lebanon, 7 -- shorter than most, still valid
            "+8613912345678" }) { // China, 11
            PhoneNumberValidator.Result result = PhoneNumberValidator.normalise(number);
            assertTrue(result.isValid(), number + " should be accepted: " + result.getError());
            assertEquals(number, result.getValue(), "E.164 should round-trip unchanged");
        }
    }

    @Test
    void spacingAndPunctuationAreNormalisedAway() {
        // People paste numbers with the formatting their phone showed them.
        for (String messy : new String[] {
            "+1 (202) 555-0143", "+1-202-555-0143", "+1 202 555 0143" }) {
            PhoneNumberValidator.Result result = PhoneNumberValidator.normalise(messy);
            assertTrue(result.isValid(), messy + " should be accepted");
            assertEquals("+12025550143", result.getValue());
        }
    }

    @Test
    void theRightLengthIsNotEnough() {
        // Ten digits, US country code, and still not a number anyone can ring: no US area code
        // begins 000. A length rule accepts this; the metadata does not.
        PhoneNumberValidator.Result result = PhoneNumberValidator.normalise("+10001234567");
        assertFalse(result.isValid(), "a well-formed but unassigned number must be rejected");
    }

    @Test
    void tooFewOrTooManyDigitsAreRejected() {
        assertFalse(PhoneNumberValidator.normalise("+1202555").isValid(), "too short");
        assertFalse(PhoneNumberValidator.normalise("+120255501431234").isValid(), "too long");
    }

    @Test
    void aMissingCountryCodeIsRefusedRatherThanGuessed() {
        // Guessing a country files the number under the wrong one, which only surfaces when
        // somebody tries to ring it.
        PhoneNumberValidator.Result result = PhoneNumberValidator.normalise("2025550143");
        assertFalse(result.isValid());
        assertTrue(result.getError().contains("+"), "the message should say what is missing");
    }

    @Test
    void anUnknownCountryCodeIsRejected() {
        assertFalse(PhoneNumberValidator.normalise("+9991234567").isValid());
    }

    @Test
    void blankClearsTheFieldRatherThanFailingTheSave() {
        for (String blank : new String[] { null, "", "   " }) {
            PhoneNumberValidator.Result result = PhoneNumberValidator.normalise(blank);
            assertTrue(result.isValid(), "a phone number is optional");
            assertNull(result.getValue(), "blank should store as null, not an empty string");
        }
    }

    @Test
    void theCountryCanBeRecoveredFromTheStoredNumber() {
        // This is what lets one column carry both, so the selector can be repopulated on edit.
        assertEquals("US", PhoneNumberValidator.regionOf("+12025550143"));
        // A London landline rather than a +44 mobile: Guernsey, Jersey and the Isle of Man
        // share +44, and mobile ranges there resolve to GG/JE/IM rather than GB.
        assertEquals("GB", PhoneNumberValidator.regionOf("+442079460958"));
        assertEquals("PK", PhoneNumberValidator.regionOf("+923001234567"));
        assertNull(PhoneNumberValidator.regionOf("not a number"));
    }
}
