package process.emailer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the account-created message the way the mailer does.
 *
 * The tenant welcome shipped reading "Hello $contact_name" because the context puts the body map
 * under a single "request" key and Velocity prints an undefined variable literally rather than
 * failing. Templates are not compiled, so only a render catches it. This one has the same shape
 * plus a conditional block, which is the other thing that can silently do nothing.
 */
public class UserWelcomeTemplateTest {

    private VelocityManager velocityManager;

    @BeforeEach
    void setUp() {
        this.velocityManager = new VelocityManager();
        // @PostConstruct does not run outside the container.
        this.velocityManager.init();
    }

    private Map<String, Object> body() {
        Map<String, Object> body = new HashMap<>();
        body.put("full_name", "Rosa Delgado");
        body.put("organisation_name", "Litware Financial");
        body.put("username", "rosa.delgado@litware.example");
        body.put("role_label", "Tenant user");
        body.put("created_by_name", "Lia Magaly");
        body.put("sign_in_url", "http://localhost:4400/login");
        return body;
    }

    private String render(Map<String, Object> body) {
        return this.velocityManager.getResponseMessage(TemplateType.USER_WELCOME, body);
    }

    @Test
    void everyValueReachesTheMessage() {
        Map<String, Object> body = body();
        body.put("temporary_password", "Kv7RtQm3XbNp9Wsd");
        String message = render(body);
        assertTrue(message.contains("Rosa Delgado"), "full name missing");
        assertTrue(message.contains("Litware Financial"), "organisation missing");
        assertTrue(message.contains("rosa.delgado@litware.example"), "username missing");
        assertTrue(message.contains("Kv7RtQm3XbNp9Wsd"), "password missing");
        assertTrue(message.contains("Tenant user"), "role missing");
        assertTrue(message.contains("Lia Magaly"), "creator missing");
        assertTrue(message.contains("http://localhost:4400/login"), "sign-in link missing");
    }

    @Test
    void noPlaceholderSurvivesUnreplaced() {
        Map<String, Object> body = body();
        body.put("temporary_password", "Kv7RtQm3XbNp9Wsd");
        String message = render(body);
        for (String name : new String[] {
            "$full_name", "$organisation_name", "$username", "$temporary_password",
            "$sign_in_url", "$role_label", "$created_by_name", "$request.get" }) {
            assertFalse(message.contains(name), "left unreplaced in the message: " + name);
        }
    }

    @Test
    void anAdminChosenPasswordIsNeverPrinted() {
        // The whole point of the second path: no credential in the message at all.
        String message = render(body());
        assertFalse(message.contains("One-time password"),
            "the password block rendered when there is no password to show");
        assertTrue(message.contains("was set for you when the account was created"),
            "the message does not explain where to get the password");
        assertTrue(message.contains("rosa.delgado@litware.example"),
            "the username should still be there");
    }

    @Test
    void theTextIsReadableRatherThanMisdecoded() {
        String message = render(body());
        // A UTF-8 byte sequence read as Latin-1 shows up as these.
        assertFalse(message.contains("Â") || message.contains("â"),
            "the template is being read with the wrong encoding");
    }
}
