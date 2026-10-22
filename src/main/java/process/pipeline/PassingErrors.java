package process.pipeline;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.regex.Pattern;

/**
 * Owner 2026-10-21: an error that says to try again -- the other side was away for a moment (a service or storage
 * restarting). A connection that could not be made, was reset or timed out on the network, or a service's answer of HTTP
 * 429, 502, 503 or 504 (every backing words a service's answer "(HTTP n"). A step that overran its own timeout is not one:
 * that is the step's limit, and a second try costs the same time again.
 */
final class PassingErrors {

    /** The waits before each extra try of a step whose author set no retry. */
    static final int[] WAITS_SECONDS = {5, 15, 45};

    private static final Pattern BUSY = Pattern.compile("\\(HTTP (429|502|503|504)\\b");

    private PassingErrors() {
    }

    static boolean is(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 12; t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (t instanceof ConnectException || t instanceof NoRouteToHostException || t instanceof SocketTimeoutException
                || t instanceof UnknownHostException || t instanceof HttpTimeoutException || t instanceof SocketException
                || t instanceof InterruptedIOException && "timeout".equals(t.getMessage())) {
                return true;
            }
            String message = t.getMessage();
            if (message != null && BUSY.matcher(message).find()) {
                return true;
            }
        }
        return false;
    }
}
