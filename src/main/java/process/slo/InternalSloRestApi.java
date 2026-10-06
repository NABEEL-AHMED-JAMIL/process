package process.slo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Map;

/**
 * The pipeline execution SLI for any past window, from stored rows (MIG-196): POST /internal/slo/runs
 * {"from": "2026-09-01T00:00:00Z", "to": "2026-10-01T00:00:00Z"}, internal token only. Either bound may be left out:
 * "to" is now, "from" is 28 days before "to" -- the SLO's window. Instants, with their offset.
 */
@RestController
@RequestMapping("/internal/slo")
public class InternalSloRestApi {

    /** The SLO's rolling window (docs/SLO.md). */
    static final Duration DEFAULT_WINDOW = Duration.ofDays(28);
    /** A longer question is a batch job's, not a request's. */
    static final Duration LONGEST = Duration.ofDays(400);

    private final Logger logger = LoggerFactory.getLogger(InternalSloRestApi.class);
    private final RunSloReport report;
    private final byte[] token;
    private final Clock clock;

    @Autowired
    public InternalSloRestApi(RunSloReport report, @Value("${internal.service-token:}") String token) {
        this(report, token, Clock.systemUTC());
    }

    InternalSloRestApi(RunSloReport report, String token, Clock clock) {
        this.report = report;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    @PostMapping(value = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> runs(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody(required = false) Map<String, Object> body) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        Instant to;
        Instant from;
        try {
            to = instant(body, "to", this.clock.instant());
            from = instant(body, "from", to.minus(DEFAULT_WINDOW));
        } catch (DateTimeParseException unreadable) {
            return badRequest("from and to are instants with an offset, e.g. 2026-09-01T00:00:00Z.");
        }
        if (!from.isBefore(to)) {
            return badRequest("from must be before to.");
        }
        if (Duration.between(from, to).compareTo(LONGEST) > 0) {
            return badRequest("Ask for at most " + LONGEST.toDays() + " days at a time.");
        }
        return ResponseEntity.ok(this.report.measure(from, to).toMap());
    }

    private static Instant instant(Map<String, Object> body, String key, Instant fallback) {
        Object value = body == null ? null : body.get(key);
        return value == null || String.valueOf(value).trim().isEmpty() ? fallback : Instant.parse(String.valueOf(value).trim());
    }

    private static ResponseEntity<?> badRequest(String message) {
        return new ResponseEntity<>(Collections.singletonMap("message", message), HttpStatus.BAD_REQUEST);
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            this.logger.warn("Refused an SLO report without the internal token.");
        }
        return ok;
    }
}
