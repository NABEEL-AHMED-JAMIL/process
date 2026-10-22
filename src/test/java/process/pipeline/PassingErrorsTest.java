package process.pipeline;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** Owner 2026-10-21: which errors say to try again -- the other side away for a moment -- and which do not. */
class PassingErrorsTest {

    @Test
    void aConnectionThatFailedOrABusyAnswerSaysToTryAgain() {
        assertThat(PassingErrors.is(new ConnectException("Connection refused"))).isTrue();
        assertThat(PassingErrors.is(new SocketException("Network is unreachable"))).isTrue();
        assertThat(PassingErrors.is(new SocketTimeoutException("Read timed out"))).isTrue();
        assertThat(PassingErrors.is(new InterruptedIOException("timeout"))).isTrue();
        assertThat(PassingErrors.is(new IllegalStateException("the call failed", new ConnectException("refused")))).as("as a cause").isTrue();
        for (int code : new int[] {429, 502, 503, 504}) {
            assertThat(PassingErrors.is(new IOException("media-service could not queue the report (HTTP " + code + ", X): try again"))).isTrue();
        }
    }

    @Test
    void aRefusalABadAnswerOrAStepsOwnFailureDoesNot() {
        for (int code : new int[] {400, 401, 403, 404, 409, 422, 500, 5020}) {
            assertThat(PassingErrors.is(new IOException("refused (HTTP " + code + "): no"))).as("HTTP " + code).isFalse();
        }
        assertThat(PassingErrors.is(new IllegalArgumentException("The type has no table 'lines'"))).isFalse();
        assertThat(PassingErrors.is(new InterruptedIOException("the read was stopped"))).isFalse();
        assertThat(PassingErrors.is(null)).isFalse();
    }
}
