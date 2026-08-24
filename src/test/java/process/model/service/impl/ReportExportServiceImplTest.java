package process.model.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import process.model.dto.ReportExportRequestDto;
import process.model.dto.ResponseDto;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The export's two risky halves: the file it writes, and where it is allowed to send it.
 *
 * Conversion and bucket writes are not exercised here -- those are LibreOffice and MinIO, and
 * a test that stubs them proves only that the stubs were called.
 */
class ReportExportServiceImplTest {

    private ReportExportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReportExportServiceImpl(null, null);
    }

    private ResponseDto exportWith(ReportExportRequestDto dto) {
        return service.export(dto);
    }

    private ReportExportRequestDto grid() {
        ReportExportRequestDto dto = new ReportExportRequestDto();
        dto.setTitle("Runs by task");
        dto.setColumns(Arrays.asList("Task", "Completed"));
        dto.setRows(Collections.singletonList(Arrays.asList("Hurricane Data Task", 286)));
        dto.setFormat("csv");
        dto.setDestination("download");
        return dto;
    }

    // ---- the file ----------------------------------------------------------------------

    @Test
    @DisplayName("header row then one line per row")
    void buildsGrid() {
        String csv = service.toCsv(Arrays.asList("A", "B"),
            Arrays.asList(Arrays.asList("x", 1), Arrays.asList("y", 2)));
        assertEquals("A,B\nx,1\ny,2", csv);
    }

    @Test
    @DisplayName("a comma in a value does not become a new column")
    void quotesCommas() {
        String csv = service.toCsv(Arrays.asList("A"),
            Collections.singletonList(Collections.singletonList("Batch, Demo")));
        assertTrue(csv.contains("\"Batch, Demo\""), csv);
    }

    @Test
    @DisplayName("an embedded quote is doubled")
    void escapesQuotes() {
        String csv = service.toCsv(Arrays.asList("A"),
            Collections.singletonList(Collections.singletonList("said \"no\"")));
        assertTrue(csv.contains("\"said \"\"no\"\"\""), csv);
    }

    @Test
    @DisplayName("a newline inside a value keeps the row intact")
    void quotesNewlines() {
        String csv = service.toCsv(Arrays.asList("A"),
            Collections.singletonList(Collections.singletonList("one\ntwo")));
        assertTrue(csv.contains("\"one\ntwo\""), csv);
    }

    @Test
    @DisplayName("a cell that opens like a formula is defused")
    void defusesFormulas() {
        for (String payload : new String[]{"=1+1", "@SUM(A1)", "=HYPERLINK(\"http://x\")", "=cmd|calc"}) {
            String csv = service.toCsv(Arrays.asList("A"),
                Collections.singletonList(Collections.singletonList(payload)));
            String cell = csv.split("\n")[1];
            assertTrue(cell.startsWith("'") || cell.startsWith("\"'"),
                "not defused: " + cell);
        }
    }

    @Test
    @DisplayName("a negative number stays a number a sheet can total")
    void leavesNumbersAlone() {
        String csv = service.toCsv(Arrays.asList("A"),
            Collections.singletonList(Collections.<Object>singletonList(-5)));
        assertEquals("A\n-5", csv);
    }

    @Test
    @DisplayName("a null cell is empty rather than the word null")
    void nullCells() {
        String csv = service.toCsv(Arrays.asList("A", "B"),
            Collections.singletonList(Arrays.asList(null, "x")));
        assertEquals("A,B\n,x", csv);
    }

    // ---- validation --------------------------------------------------------------------

    @Test
    @DisplayName("a grid with no columns is refused")
    void refusesEmptyGrid() {
        ReportExportRequestDto dto = grid();
        dto.setColumns(Collections.emptyList());
        assertEquals("ERROR", exportWith(dto).getStatus());
    }

    @Test
    @DisplayName("only csv and xlsx are offered")
    void refusesOtherFormats() {
        ReportExportRequestDto dto = grid();
        dto.setFormat("exe");
        ResponseDto out = exportWith(dto);
        assertEquals("ERROR", out.getStatus());
        assertTrue(out.getMessage().contains("csv or xlsx"), out.getMessage());
    }

    @Test
    @DisplayName("an unknown destination is refused rather than defaulted")
    void refusesUnknownDestination() {
        ReportExportRequestDto dto = grid();
        dto.setDestination("email");
        assertEquals("ERROR", exportWith(dto).getStatus());
    }

    @Test
    @DisplayName("a download returns the file inline")
    void downloadsInline() {
        ResponseDto out = exportWith(grid());
        assertEquals("SUCCESS", out.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) out.getData();
        assertTrue(String.valueOf(data.get("filename")).startsWith("runs-by-task-"));
        assertTrue(String.valueOf(data.get("filename")).endsWith(".csv"));
        String decoded = new String(Base64.getDecoder().decode(String.valueOf(data.get("content"))));
        assertTrue(decoded.startsWith("Task,Completed"), decoded);
    }

    @Test
    @DisplayName("saving to a bucket needs a bucket")
    void bucketNeedsABucket() {
        ReportExportRequestDto dto = grid();
        dto.setDestination("bucket");
        assertEquals("ERROR", exportWith(dto).getStatus());
    }

    // ---- where it may send ---------------------------------------------------------------

    @Test
    @DisplayName("submitting needs somewhere to submit to")
    void submitNeedsUrl() {
        ReportExportRequestDto dto = grid();
        dto.setDestination("submit");
        assertEquals("ERROR", exportWith(dto).getStatus());
    }

    @Test
    @DisplayName("the server will not be made to call its own network")
    void refusesInternalTargets() throws Exception {
        for (String target : new String[]{
                "http://127.0.0.1:9098/api/v1/notify.json",
                "http://localhost/admin",
                "http://169.254.169.254/latest/meta-data/",   // cloud metadata
                "http://10.0.0.5/internal",
                "http://192.168.1.1/"}) {
            ReportExportRequestDto dto = grid();
            dto.setDestination("submit");
            dto.setSubmitUrl(target);
            ResponseDto out = exportWith(dto);
            assertEquals("ERROR", out.getStatus(), "allowed: " + target);
            assertTrue(out.getMessage().contains("own network") || out.getMessage().contains("resolved"),
                target + " -> " + out.getMessage());
        }
    }

    @Test
    @DisplayName("only http and https, so file:// and gopher:// cannot be reached")
    void refusesOtherSchemes() {
        for (String target : new String[]{"file:///etc/passwd", "gopher://x/", "ftp://host/f"}) {
            ReportExportRequestDto dto = grid();
            dto.setDestination("submit");
            dto.setSubmitUrl(target);
            ResponseDto out = exportWith(dto);
            assertEquals("ERROR", out.getStatus(), "allowed: " + target);
            assertTrue(out.getMessage().contains("http and https"), out.getMessage());
        }
    }

    @Test
    @DisplayName("a deployment can opt in to internal targets deliberately")
    void internalAllowedWhenConfigured() throws Exception {
        Field flag = ReportExportServiceImpl.class.getDeclaredField("allowInternalSubmit");
        flag.setAccessible(true);
        flag.setBoolean(service, true);
        ReportExportRequestDto dto = grid();
        dto.setDestination("submit");
        dto.setSubmitUrl("http://127.0.0.1:1/nothing-listening");
        // Reaches the request rather than the guard: the failure is now the connection.
        ResponseDto out = exportWith(dto);
        assertEquals("ERROR", out.getStatus());
        assertFalse(out.getMessage().contains("own network"), out.getMessage());
    }
}
