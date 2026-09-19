package process.api;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import process.util.UserNameResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;
import process.billing.MeterClient;
import process.engine.cron.UsageMeasurerCron;
import process.model.dto.ResponseDto;
import process.security.TenantContext;
import process.util.ProcessUtil;

import java.time.LocalDate;

/**
 * Cost & usage: what the console reads from the meter for a workspace.
 *
 * A tenant admin reads their own workspace whatever tenantId the request names; a platform
 * admin may name any. The meter answers priced daily rows; this only decides who may ask.
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/billing.json")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class BillingRestApi {

    private final Logger logger = LoggerFactory.getLogger(BillingRestApi.class);
    private final MeterClient meter;
    private final UsageMeasurerCron measurer;
    private final UserNameResolver names;

    public BillingRestApi(MeterClient meter, UsageMeasurerCron measurer, UserNameResolver names) {
        this.meter = meter; this.measurer = measurer; this.names = names;
    }

    /** The meter knows who by id; a person reading "who deleted what" wants the name. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> withActorNames(Map<String, Object> answer) {
        Object rows = answer.get("rows");
        if (!(rows instanceof List)) {
            return answer;
        }
        Set<Long> ids = new HashSet<>();
        for (Object row : (List<Object>) rows) {
            Object id = ((Map<String, Object>) row).get("actor_user_id");
            if (id instanceof Number) ids.add(((Number) id).longValue());
        }
        Map<Long, String> resolved = ids.isEmpty() ? new HashMap<>() : this.names.namesFor(ids);
        for (Object row : (List<Object>) rows) {
            Map<String, Object> r = (Map<String, Object>) row;
            Object id = r.get("actor_user_id");
            r.put("actor_name", id instanceof Number ? resolved.get(((Number) id).longValue()) : null);
        }
        return answer;
    }

    /** Tonight's measurement, now: storage kept, seats and topics for every workspace. Platform admin. */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @RequestMapping(value = "/measure", method = RequestMethod.POST)
    public ResponseEntity<?> measure() {
        if (!this.meter.isConfigured()) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "Metering is not configured on this console."), HttpStatus.OK);
        }
        int events = this.measurer.measure(LocalDate.now());
        this.meter.flush();
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, String.format("Measured: %d event(s) reported.", events), events), HttpStatus.OK);
    }

    private Long scope(Long tenantId) {
        if (TenantContext.isPlatformAdmin()) {
            return tenantId;
        }
        return TenantContext.getTenantId();
    }

    private ResponseEntity<?> answer(String what, JsonSupplier body) {
        if (!this.meter.isConfigured()) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "Metering is not configured on this console."), HttpStatus.OK);
        }
        try {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, what, body.get()), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.warn("billing {} failed: {}", what, ex.toString());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "The metering service did not answer: " + ex.getMessage()), HttpStatus.OK);
        }
    }

    @RequestMapping(value = "/usage", method = RequestMethod.GET)
    public ResponseEntity<?> usage(@RequestParam(required = false) Long tenantId, @RequestParam String from, @RequestParam String to,
        @RequestParam(required = false, defaultValue = "meter") String groupBy) {
        Long scoped = this.scope(tenantId);
        if (scoped == null && !"tenant".equals(groupBy) && !TenantContext.isPlatformAdmin()) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "No workspace to read."), HttpStatus.OK);
        }
        return this.answer("Usage read.", () -> this.meter.usage(scoped, LocalDate.parse(from), LocalDate.parse(to), groupBy));
    }

    @RequestMapping(value = "/subjects", method = RequestMethod.GET)
    public ResponseEntity<?> subjects(@RequestParam(required = false) Long tenantId, @RequestParam("meter") String meterName,
        @RequestParam String from, @RequestParam String to, @RequestParam(required = false, defaultValue = "50") int limit) {
        Long scoped = this.scope(tenantId);
        return this.answer("Subjects read.", () -> this.withActorNames(this.meter.subjects(scoped, meterName, LocalDate.parse(from), LocalDate.parse(to), limit)));
    }

    @RequestMapping(value = "/events", method = RequestMethod.GET)
    public ResponseEntity<?> events(@RequestParam(required = false) Long tenantId, @RequestParam(value = "meter", required = false) String meterName,
        @RequestParam(required = false) String from, @RequestParam(required = false) String to,
        @RequestParam(required = false, defaultValue = "1") int page, @RequestParam(required = false, defaultValue = "100") int limit) {
        Long scoped = this.scope(tenantId);
        return this.answer("Events read.", () -> this.withActorNames(this.meter.events(scoped, meterName,
            from == null ? null : LocalDate.parse(from), to == null ? null : LocalDate.parse(to), page, limit)));
    }

    @RequestMapping(value = "/rateCard", method = RequestMethod.GET)
    public ResponseEntity<?> rateCard() {
        return this.answer("Rate card read.", this.meter::rateCard);
    }

    /** Rolls the last two days again, for a Refresh that wants the latest events priced now. */
    @RequestMapping(value = "/refresh", method = RequestMethod.POST)
    public ResponseEntity<?> refresh() {
        return this.answer("Rolled up.", () -> this.meter.rollup(48));
    }

    @RequestMapping(value = "/health", method = RequestMethod.GET)
    public ResponseEntity<?> health() {
        return this.answer("Meter health.", this.meter::health);
    }

    @FunctionalInterface
    private interface JsonSupplier { Map<String, Object> get() throws Exception; }
}
