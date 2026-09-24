package process.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MIG-115 / MIG-116: the gate's decision table where the real engine cannot be made to misbehave.
 * The probe asks DuckDB's own parser about the statement. Every answer that is not a clear one --
 * no parser function, no row, no verdict, no tree, a tree that is not JSON -- must REFUSE, because
 * a gate that opens when it is broken is not a gate. These run against a session that answers
 * exactly the broken thing, and each asserts the statement itself is never executed.
 */
class StatementGateFailClosedTest {

    private static final String READS_ONLY = "Analytics Studio only runs queries that read. This statement asks for something else, "
        + "so it was not run.";

    /** A session whose parse probe answers one row with these columns. */
    private static Connection sessionAnswering(String failed, int statements, boolean statementsNull, String tree) throws SQLException {
        Connection session = mock(Connection.class);
        PreparedStatement probe = mock(PreparedStatement.class);
        ResultSet row = mock(ResultSet.class);
        when(session.prepareStatement(anyString())).thenReturn(probe);
        when(probe.executeQuery()).thenReturn(row);
        when(row.next()).thenReturn(true, false);
        when(row.getString("failed")).thenReturn(failed);
        when(row.getInt("statements")).thenReturn(statements);
        when(row.wasNull()).thenReturn(statementsNull);
        when(row.getString("error_type")).thenReturn(null);
        when(row.getString("message")).thenReturn(null);
        when(row.getString("tree")).thenReturn(tree);
        return session;
    }

    @Test
    void nothingToRunIsSaidBeforeTheSessionIsTouched() {
        for (String blank : new String[] {null, "", "   ", "\n\t "}) {
            Connection session = mock(Connection.class);
            assertThatThrownBy(() -> StatementGate.admit(session, blank, 30)).as("<%s>", blank)
                .isInstanceOf(AnalyticsException.class).hasMessage("There is no query to run.");
            verifyNoInteractions(session);
        }
    }

    @Test
    void aNulByteIsRefusedBeforeTheParserSeesIt() {
        Connection session = mock(Connection.class);
        assertThatThrownBy(() -> StatementGate.admit(session, "SELECT 1" + (char) 0 + "; DROP TABLE t", 30))
            .isInstanceOf(AnalyticsException.class);
        verifyNoInteractions(session);
    }

    @Test
    void aSessionWithoutTheParserFunctionRefusesRatherThanGuesses() throws Exception {
        Connection session = mock(Connection.class);
        when(session.prepareStatement(anyString())).thenThrow(
            new SQLException("Catalog Error: Scalar Function with name json_serialize_sql does not exist!"));
        assertThatThrownBy(() -> StatementGate.admit(session, "SELECT 1", 30))
            .isInstanceOf(SQLException.class)
            .hasMessageStartingWith("Analytics could not check the statement before running it: ");
    }

    @Test
    void anEmptyAnswerFromTheProbeIsARefusal() throws Exception {
        Connection session = mock(Connection.class);
        PreparedStatement probe = mock(PreparedStatement.class);
        ResultSet none = mock(ResultSet.class);
        when(session.prepareStatement(anyString())).thenReturn(probe);
        when(probe.executeQuery()).thenReturn(none);
        when(none.next()).thenReturn(false);
        assertThatThrownBy(() -> StatementGate.admit(session, "SELECT 1", 30))
            .isInstanceOf(AnalyticsException.class).hasMessage(READS_ONLY);
    }

    @Test
    void aNullVerdictIsARefusal() throws Exception {
        Connection session = sessionAnswering(null, 1, false, "{}");
        assertThatThrownBy(() -> StatementGate.admit(session, "SELECT 1", 30))
            .isInstanceOf(AnalyticsException.class).hasMessage(READS_ONLY);
    }

    @Test
    void aReadWithNoTreeOrAnUnreadableTreeIsRefusedAndNeverRun() throws Exception {
        // A tree that reads but is not the parser's answer -- empty, null, an array, a number, an
        // object with no statements or with two -- held no nodes to refuse and was admitted.
        List<String> admitted = new ArrayList<>();
        for (String tree : new String[] {null, "{not json", "", "   ", "null", "[]", "42", "{}", "{\"statements\": []}",
            "{\"statements\": [{}, {}]}"}) {
            Connection session = sessionAnswering("false", 1, false, tree);
            try {
                StatementGate.admit(session, "SELECT 1", 30);
                admitted.add("<" + tree + ">");
            } catch (AnalyticsException refused) {
                // Only the probe was prepared: the statement itself never reached the engine.
                verify(session, never()).createStatement();
            }
        }
        assertThat(admitted).isEmpty();
    }

    @Test
    void theTreeWalkStopsAtFourHundredLevels() {
        // Recursive over a request-supplied tree: a StackOverflowError is an Error, and would get
        // past every catch block the endpoints have. The ceiling is part of the defence.
        assertThat(ReflectionTestUtils.getField(StatementGate.class, "MAX_TREE_DEPTH")).isEqualTo(400);
    }
}
