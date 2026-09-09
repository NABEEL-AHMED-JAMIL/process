package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.core.io.ByteArrayResource;
import process.model.dto.ReportExportRequestDto;
import process.model.service.StorageBrowserService;
import process.model.dto.ResponseDto;
import process.util.ProcessUtil;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Turns a report grid into a file, and puts it where the caller asked.
 *
 * Three destinations, one path to each: hand it back for download, write it into a bucket, or
 * post it to a configured endpoint. The file itself is built once regardless -- CSV always,
 * converted to xlsx through the same LibreOffice route the document converter and the job
 * assistant already use, so there is one conversion implementation rather than three.
 *
 * @author Nabeel Ahmed
 */
@Service
public class ReportExportServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(ReportExportServiceImpl.class);

    /** Enough for a large pivot; beyond this the answer is a query, not a spreadsheet. */
    private static final int MAX_ROWS = 50_000;
    private static final int MAX_CELL_CHARS = 32_000;

    /**
     * Cells opening with these are formulas to a spreadsheet, and this file exists to be opened
     * in one. The grid's values came from the caller, so the first character of a cell is not
     * ours to trust; a leading apostrophe makes Excel read the rest as text.
     */
    private static final Pattern FORMULA_LEAD = Pattern.compile("^[=+\\-@\\t\\r]");

    private final FileChatExtractionServiceImpl extractionService;
    private final StorageBrowserService storageService;
    private final QueryService queryService;
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Whether a report may be submitted to an address inside the deployment's own network.
     *
     * Off by default. Posting to a caller-supplied URL from the server is request forgery
     * unless something says otherwise: without this guard, "submit the result" reaches every
     * internal service the container can, including the cloud metadata endpoint.
     */
    @Value("${report.submit.allow-internal:false}")
    private boolean allowInternalSubmit;

    public ReportExportServiceImpl(FileChatExtractionServiceImpl extractionService,
                                   StorageBrowserService storageService,
                                   QueryService queryService) {
        this.extractionService = extractionService;
        this.storageService = storageService;
        this.queryService = queryService;
    }

    /**
     * The rows the report screen pivots.
     *
     * Columnar rather than a list of objects: 1,500 rows of repeated key names is most of the
     * payload, and the screen indexes into dictionaries anyway. Capped, because a report over
     * an unbounded range is a question for a query tool rather than a browser.
     */
    public ResponseDto runRows(String startDate, String endDate) {
        List<Object[]> result;
        try {
            result = this.queryService.executeQuery(this.queryService.runReportRows(startDate, endDate));
        } catch (Exception ex) {
            logger.error("Report: could not read run rows", ex);
            return new ResponseDto(ERROR, "Could not read the runs for that range.");
        }
        if (result == null) result = Collections.emptyList();

        boolean truncated = result.size() > MAX_ROWS;
        if (truncated) result = result.subList(0, MAX_ROWS);

        // dictionaries per dimension, rows as indexes into them
        List<String> tasks = new ArrayList<>(), statuses = new ArrayList<>(),
                     owners = new ArrayList<>(), days = new ArrayList<>(),
                     tenants = new ArrayList<>();
        Map<String,Integer> ti = new HashMap<>(), si = new HashMap<>(),
                            oi = new HashMap<>(), di = new HashMap<>(),
                            ni = new HashMap<>();
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] r : result) {
            rows.add(Arrays.asList(
                intern(tasks, ti, text(r[0])),
                intern(statuses, si, text(r[1])),
                intern(owners, oi, text(r[2])),
                intern(days, di, text(r[3])),
                r[4] == null ? -1 : Integer.valueOf(String.valueOf(r[4])),
                text(r[5]),
                r[6] == null ? null : Long.valueOf(String.valueOf(r[6])),
                // Appended at index 7. Every existing index keeps pointing where it did, so an
                // older client reading seven elements is unaffected.
                intern(tenants, ni, text(r.length > 7 ? r[7] : null)),
                // Double, not Integer: this one carries two decimals on purpose.
                r.length > 8 && r[8] != null ? Double.valueOf(String.valueOf(r[8])) : -1D));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", tasks);
        data.put("status", statuses);
        data.put("owner", owners);
        data.put("day", days);
        data.put("tenant", tenants);
        data.put("rows", rows);
        data.put("truncated", truncated);
        String message = truncated
            ? String.format("Showing the most recent %,d runs; narrow the range to see the rest.", MAX_ROWS)
            : String.format("%,d run(s).", rows.size());
        return new ResponseDto(SUCCESS, message, data);
    }

    private static int intern(List<String> values, Map<String,Integer> index, String value) {
        Integer at = index.get(value);
        if (at != null) return at;
        index.put(value, values.size());
        values.add(value);
        return values.size() - 1;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public ResponseDto export(ReportExportRequestDto dto) {
        if (dto == null || dto.getColumns() == null || dto.getColumns().isEmpty()) {
            return new ResponseDto(ERROR, "The report has no columns to export.");
        }
        List<List<Object>> rows = dto.getRows() == null ? Collections.emptyList() : dto.getRows();
        if (rows.size() > MAX_ROWS) {
            return new ResponseDto(ERROR,
                String.format("This report has %,d rows; %,d is the most that can be exported.",
                    rows.size(), MAX_ROWS));
        }
        String format = value(dto.getFormat(), "csv").toLowerCase();
        if (!format.equals("csv") && !format.equals("xlsx")) {
            return new ResponseDto(ERROR, "Export format must be csv or xlsx.");
        }

        byte[] payload;
        String csv = toCsv(dto.getColumns(), rows);
        if (format.equals("xlsx")) {
            try {
                payload = this.extractionService.convertContent(
                    csv.getBytes(StandardCharsets.UTF_8), "csv", "xlsx");
            } catch (Exception ex) {
                logger.error("Report export: csv -> xlsx conversion failed", ex);
                return new ResponseDto(ERROR, "The spreadsheet could not be built: " + ex.getMessage());
            }
            if (payload == null || payload.length == 0) {
                return new ResponseDto(ERROR, "The spreadsheet came back empty.");
            }
        } else {
            payload = csv.getBytes(StandardCharsets.UTF_8);
        }

        String filename = filenameFor(dto.getTitle(), format);
        String contentType = format.equals("xlsx")
            ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            : "text/csv";

        String destination = value(dto.getDestination(), "download").toLowerCase();
        switch (destination) {
            case "download": return download(payload, filename, contentType);
            case "bucket":   return toBucket(dto, payload, filename, contentType);
            case "submit":   return submit(dto, payload, filename, contentType);
            default:
                return new ResponseDto(ERROR, "Destination must be download, bucket or submit.");
        }
    }

    // ---- destinations ---------------------------------------------------------------------

    private ResponseDto download(byte[] payload, String filename, String contentType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("filename", filename);
        data.put("contentType", contentType);
        data.put("content", Base64.getEncoder().encodeToString(payload));
        return new ResponseDto(SUCCESS, "Export ready.", data);
    }

    private ResponseDto toBucket(ReportExportRequestDto dto, byte[] payload,
                                 String filename, String contentType) {
        if (ProcessUtil.isNull(dto.getBucket()) || dto.getBucket().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Choose a bucket to save into.");
        }
        String folder = value(dto.getFolder(), "reports").trim();
        // A key is assembled here rather than taken from the caller: a folder containing ..
        // would otherwise write outside the intended prefix.
        String key = folder.replaceAll("^/+", "").replaceAll("\\.\\.", "").replaceAll("/+$", "")
            + "/" + filename;
        try {
            this.storageService.uploadObject(dto.getBucket().trim(), key,
                new ByteArrayInputStream(payload), payload.length, contentType);
        } catch (Exception ex) {
            logger.error("Report export: could not write {} to {}", key, dto.getBucket(), ex);
            return new ResponseDto(ERROR, "Could not save to that bucket: " + ex.getMessage());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bucket", dto.getBucket().trim());
        data.put("key", key);
        data.put("bytes", payload.length);
        return new ResponseDto(SUCCESS, String.format("Saved to %s/%s", dto.getBucket().trim(), key), data);
    }

    private ResponseDto submit(ReportExportRequestDto dto, byte[] payload,
                               String filename, String contentType) {
        if (ProcessUtil.isNull(dto.getSubmitUrl()) || dto.getSubmitUrl().trim().isEmpty()) {
            return new ResponseDto(ERROR, "There is no endpoint configured to submit to.");
        }
        String target = dto.getSubmitUrl().trim();
        String rejection = rejectUnsafeTarget(target);
        if (rejection != null) {
            return new ResponseDto(ERROR, rejection);
        }
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            ByteArrayResource file = new ByteArrayResource(payload) {
                @Override public String getFilename() { return filename; }
            };
            body.add("file", file);
            body.add("title", value(dto.getTitle(), "report"));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            ResponseEntity<String> response = this.restTemplate.exchange(
                URI.create(target), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", response.getStatusCodeValue());
            data.put("response", trim(response.getBody()));
            return new ResponseDto(SUCCESS,
                String.format("Submitted to %s (HTTP %s).", target, response.getStatusCodeValue()), data);
        } catch (Exception ex) {
            logger.error("Report export: submit to {} failed", target, ex);
            return new ResponseDto(ERROR, "The endpoint did not accept it: " + ex.getMessage());
        }
    }

    /**
     * Returns why this target must not be called, or null when it is acceptable.
     *
     * The caller supplies this address, so the server must not be turned into a proxy for
     * whatever it can reach. Loopback, link-local and private ranges are refused unless the
     * deployment opts in, which is what keeps "submit the result" from reaching the metadata
     * service or a neighbouring container.
     */
    private String rejectUnsafeTarget(String target) {
        URI uri;
        try {
            uri = URI.create(target);
        } catch (Exception ex) {
            return "That does not look like a valid URL.";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "Only http and https endpoints can be submitted to.";
        }
        if (uri.getHost() == null) {
            return "That URL has no host.";
        }
        if (this.allowInternalSubmit) {
            return null;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {
                    return "That address is inside this deployment's own network. "
                        + "Set report.submit.allow-internal=true if that is intended.";
                }
            }
        } catch (Exception ex) {
            return "That host could not be resolved.";
        }
        return null;
    }

    // ---- csv ------------------------------------------------------------------------------

    /** The grid as CSV. Shared by every destination, so all three carry identical content. */
    String toCsv(List<String> columns, List<List<Object>> rows) {
        StringBuilder out = new StringBuilder();
        out.append(joinRow(new ArrayList<Object>(columns)));
        for (List<Object> row : rows) {
            out.append('\n').append(joinRow(row));
        }
        return out.toString();
    }

    private String joinRow(List<Object> cells) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) line.append(',');
            line.append(escape(cells.get(i)));
        }
        return line.toString();
    }

    private String escape(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        if (text.length() > MAX_CELL_CHARS) text = text.substring(0, MAX_CELL_CHARS);
        // Numbers are left alone: prefixing them would turn a figure into text a sheet
        // cannot total. Only text that opens like a formula is defused.
        boolean numeric = value instanceof Number;
        if (!numeric && FORMULA_LEAD.matcher(text).find() && !isNumeric(text)) {
            text = "'" + text;
        }
        if (text.contains("\"") || text.contains(",") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private boolean isNumeric(String text) {
        try { Double.parseDouble(text.trim()); return true; } catch (Exception ex) { return false; }
    }

    // ---- helpers --------------------------------------------------------------------------

    private String filenameFor(String title, String format) {
        String base = value(title, "report").toLowerCase()
            .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "report";
        if (base.length() > 60) base = base.substring(0, 60);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return base + "-" + stamp + "." + format;
    }

    private static String value(String given, String fallback) {
        return ProcessUtil.isNull(given) || given.trim().isEmpty() ? fallback : given.trim();
    }

    private static String trim(String body) {
        if (body == null) return null;
        return body.length() > 500 ? body.substring(0, 500) + "…" : body;
    }
}
