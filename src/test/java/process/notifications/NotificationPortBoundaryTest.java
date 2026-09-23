package process.notifications;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Notifications is its own service (MIG-22). Process raises contract events through
 * NotificationPort, whose one implementation writes them to the outbox, and delivers nothing
 * itself: no STOMP broker, no mailer or templates, no notification store.
 *
 * MIG-20 held the line at the port inside process; this holds it at the process boundary, so an
 * in-process delivery path cannot quietly come back. (A source scan, not ArchUnit: no new
 * dependency, and each rule is one line of grep.)
 */
class NotificationPortBoundaryTest {

    private static final Path MAIN = Paths.get("src", "main", "java", "process");
    private static final Path RESOURCES = Paths.get("src", "main", "resources");

    /** What only a delivering Notifications needs. */
    private static final List<String> DELIVERY_ONLY = Arrays.asList(
        "org.springframework.messaging.simp", "EnableWebSocketMessageBroker",
        "org.apache.velocity", "software.amazon.awssdk.services.ses", "javax.mail", "MimeMessageHelper");

    @Test
    void processDeliversNothingItself() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                String source = new String(Files.readAllBytes(file));
                for (String marker : DELIVERY_ONLY) {
                    if (source.contains(marker)) offenders.add(MAIN.relativize(file) + " uses " + marker);
                }
            }
        }
        assertThat(offenders).as("delivery code left in process").isEmpty();
    }

    @Test
    void theNotificationsPackagesAndResourcesAreGone() {
        for (String gone : new String[] {"socket", "emailer", "notifications/store"}) {
            assertThat(MAIN.resolve(gone)).as(gone).doesNotExist();
        }
        assertThat(RESOURCES.resolve("templates")).as("mail templates").doesNotExist();
        assertThat(RESOURCES.resolve("db/notifications")).as("notifications_db changelog").doesNotExist();
    }

    /** One way out: the outbox. There is no in-process transport to fall back to. */
    @Test
    void theOutboxIsThePortsOnlyImplementation() throws IOException {
        List<String> implementations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                if (new String(Files.readAllBytes(file)).contains("implements NotificationPort")) {
                    implementations.add(file.getFileName().toString());
                }
            }
        }
        assertThat(implementations).containsExactly("OutboxNotifications.java");
    }
}
