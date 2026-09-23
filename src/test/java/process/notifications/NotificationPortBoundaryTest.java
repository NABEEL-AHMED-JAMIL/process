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
 * NotificationPort is the only way into Notifications (MIG-20).
 *
 * Everything that pushes to a socket, raises a notice or sends a mail goes through the port, so
 * that when Notifications leaves this process only the port's implementation changes. This holds
 * the edge by reading the source: a class outside the Notifications side that imports the socket or
 * mailer packages, or names the notification centre, fails the build with its file name. (A source
 * scan rather than ArchUnit: no new dependency, and the rule is one line of grep.)
 */
class NotificationPortBoundaryTest {

    private static final Path MAIN = Paths.get("src", "main", "java", "process");

    /** The Notifications side: what moves out together when the service is split. */
    private static final List<String> NOTIFICATIONS_SIDE = Arrays.asList(
        "notifications/", "socket/", "emailer/",
        // The STOMP endpoint and its SUBSCRIBE/CONNECT gate.
        "config/WebSocketConfig.java", "security/StompAuthChannelInterceptor.java",
        // The notification centre and the REST API the bell reads.
        "model/service/NotificationCenterService.java", "model/service/impl/NotificationCenterServiceImpl.java",
        "api/NotificationRestApi.java");

    @Test
    void nothingOutsideNotificationsReachesTheSocketOrMailerDirectly() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                if (NOTIFICATIONS_SIDE.stream().anyMatch(relative::startsWith)) continue;
                String source = new String(Files.readAllBytes(file));
                if (source.contains("import process.socket.") || source.contains("import process.emailer.")
                    || source.contains("NotificationCenterService")) {
                    offenders.add(relative);
                }
            }
        }
        assertThat(offenders).as("classes reaching past NotificationPort").isEmpty();
    }
}
