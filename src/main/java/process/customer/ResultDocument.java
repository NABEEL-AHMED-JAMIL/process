package process.customer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MIG-426: a run's result as a document -- its bytes, content type, file name, and the headers that say what it is made from
 * (X-Result-Source: report or rows; X-Report, X-Report-Version and X-Report-Signed for a report).
 */
public final class ResultDocument {

    public final byte[] bytes;
    public final String contentType;
    public final String fileName;
    public final Map<String, String> headers;
    /** The report it is made from (RPT-0093), its version and whether that version is signed; null for the rows. */
    public final String reportLabel;
    public final Integer reportVersion;
    public final Boolean signed;

    ResultDocument(byte[] bytes, String contentType, String fileName, String reportLabel, Integer reportVersion, Boolean signed) {
        this.bytes = bytes;
        this.contentType = contentType;
        this.fileName = fileName;
        this.reportLabel = reportLabel;
        this.reportVersion = reportVersion;
        this.signed = signed;
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Result-Source", reportLabel == null ? "rows" : "report");
        if (reportLabel != null) {
            h.put("X-Report", reportLabel);
            h.put("X-Report-Version", String.valueOf(reportVersion));
            h.put("X-Report-Signed", String.valueOf(Boolean.TRUE.equals(signed)));
        }
        this.headers = Collections.unmodifiableMap(h);
    }
}
