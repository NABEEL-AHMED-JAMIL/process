package process.emailer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the welcome message the way the mailer does.
 *
 * Written after a real one went out reading "Hello $contact_name": the context puts the body map
 * under a single "request" key, so a template has to ask for $request.get("..."), and a bare
 * $contact_name is simply undefined -- which Velocity prints literally rather than failing. A
 * template is not compiled, so nothing else catches that.
 */
public class TenantWelcomeTemplateTest {

    private VelocityManager velocityManager;

    @BeforeEach
    void setUp() {
        this.velocityManager = new VelocityManager();
        // @PostConstruct does not run outside the container.
        this.velocityManager.init();
    }

    private String render() {
        Map<String, Object> body = new HashMap<>();
        body.put("contact_name", "Dana Whitfield");
        body.put("organisation_name", "Meridian Freight Co");
        body.put("username", "dana.whitfield@meridian-freight.example");
        body.put("temporary_password", "Kv7RtQm3XbNp9Wsd");
        body.put("sign_in_url", "http://localhost:4400/login");
        return this.velocityManager.getResponseMessage(TemplateType.TENANT_WELCOME, body);
    }

    @Test
    void everyValueReachesTheMessage() {
        String message = render();
        assertTrue(message.contains("Dana Whitfield"), "contact name missing");
        assertTrue(message.contains("Meridian Freight Co"), "organisation missing");
        assertTrue(message.contains("dana.whitfield@meridian-freight.example"), "username missing");
        assertTrue(message.contains("Kv7RtQm3XbNp9Wsd"), "password missing");
        assertTrue(message.contains("http://localhost:4400/login"), "sign-in link missing");
    }

    @Test
    void noPlaceholderSurvivesUnreplaced() {
        String message = render();
        // The exact failure that shipped: a variable printed as its own name.
        for (String name : new String[] {
            "$contact_name", "$organisation_name", "$username", "$temporary_password",
            "$sign_in_url", "$request.get" }) {
            assertFalse(message.contains(name), "left unreplaced in the message: " + name);
        }
    }

    @Test
    void theTextIsReadableRatherThanMisdecoded() {
        String message = render();
        // A UTF-8 byte sequence read as Latin-1 shows up as these; an em dash became "â".
        assertFalse(message.contains("Â") || message.contains("â"),
            "the template is being read with the wrong encoding");
    }
}
