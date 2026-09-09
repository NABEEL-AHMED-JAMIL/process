package process.analytics;

/**
 * A failure with a message that is meant for a person.
 *
 * The distinction this type draws is the one the platform's error handling keeps getting wrong
 * elsewhere: some failures are the user's to fix ("pick a bucket first", "that path does not
 * exist") and some are ours ("the driver did not load"). Only the first kind should reach a
 * screen. Anything thrown as an AnalyticsException has been written for a reader; everything
 * else is caught at the service edge, logged with its stack, and reported as a generic failure
 * so an internal detail cannot leak out through a message.
 *
 * @author Nabeel Ahmed
 */
public class AnalyticsException extends Exception {

    public AnalyticsException(String message) {
        super(message);
    }

    public AnalyticsException(String message, Throwable cause) {
        super(message, cause);
    }
}
