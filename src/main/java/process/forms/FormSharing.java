package process.forms;

import org.springframework.stereotype.Service;
import process.model.dto.ResponseDto;
import process.security.TenantContext;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Sharing a form by link, as a workspace administrator does it (MIG-278): the workspace's switch (off until turned on),
 * and a form's links -- created with an expiry of 1 to 90 days, optionally one use or a cap, optionally sign-in only;
 * listed without their tokens; revoked. A form that looks up another form's answers cannot be shared: a visitor would
 * read those answers.
 */
@Service
public class FormSharing {

    static final String ADMIN_ONLY = "Only a workspace admin shares forms by link.";
    static final String TURNED_OFF = "Sharing forms by link is off for this workspace. A workspace admin turns it on first.";

    private final FormShareLinks links;
    private final FormStore store;
    private final Clock clock = Clock.systemUTC();

    public FormSharing(FormShareLinks links, FormStore store) {
        this.links = links;
        this.store = store;
    }

    /** What a link asks for when it is made. */
    public static final class CreateRequest {
        public Long formId;
        public String label;
        public Integer days;
        public Integer maxSubmissions;
        public Boolean requireSignIn;
    }

    public ResponseDto policy() {
        Long tenantId = FormService.workspace();
        if (tenantId == null) {
            return new ResponseDto(ERROR, FormService.NO_WORKSPACE);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", this.links.enabled(tenantId));
        view.put("canChange", FormService.isAdmin());
        return new ResponseDto(SUCCESS, "Sharing setting fetched.", view);
    }

    public ResponseDto setPolicy(Boolean enabled) {
        Long tenantId = FormService.workspace();
        if (tenantId == null) {
            return new ResponseDto(ERROR, FormService.NO_WORKSPACE);
        }
        if (!FormService.isAdmin()) {
            return new ResponseDto(ERROR, ADMIN_ONLY);
        }
        this.links.setEnabled(tenantId, Boolean.TRUE.equals(enabled), TenantContext.getAppUserId());
        return this.policy();
    }

    public ResponseDto list(Long formId) {
        Long tenantId = FormService.workspace();
        if (tenantId == null) {
            return new ResponseDto(ERROR, FormService.NO_WORKSPACE);
        }
        if (!FormService.isAdmin()) {
            return new ResponseDto(ERROR, ADMIN_ONLY);
        }
        Optional<FormStore.Form> form = formId == null ? Optional.empty() : this.store.find(tenantId, formId);
        if (!form.isPresent()) {
            return new ResponseDto(ERROR, FormService.FORM_NOT_FOUND);
        }
        Instant now = this.clock.instant();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FormShareLinks.Link l : this.links.list(tenantId, formId)) {
            rows.add(FormShareLinks.view(l, now));
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", this.links.enabled(tenantId));
        view.put("shareable", shareRefusal(form.get()) == null);
        view.put("whyNot", shareRefusal(form.get()));
        view.put("links", rows);
        return new ResponseDto(SUCCESS, String.format("%d link(s).", rows.size()), view);
    }

    public ResponseDto create(CreateRequest request) {
        Long tenantId = FormService.workspace();
        if (tenantId == null) {
            return new ResponseDto(ERROR, FormService.NO_WORKSPACE);
        }
        if (!FormService.isAdmin()) {
            return new ResponseDto(ERROR, ADMIN_ONLY);
        }
        if (!this.links.enabled(tenantId)) {
            return new ResponseDto(ERROR, TURNED_OFF);
        }
        Optional<FormStore.Form> form = request == null || request.formId == null ? Optional.empty() : this.store.find(tenantId, request.formId);
        if (!form.isPresent()) {
            return new ResponseDto(ERROR, FormService.FORM_NOT_FOUND);
        }
        String refusal = shareRefusal(form.get());
        if (refusal != null) {
            return new ResponseDto(ERROR, refusal);
        }
        int days = request.days == null ? 14 : request.days;
        if (days < 1 || days > FormShareLinks.MAX_DAYS) {
            return new ResponseDto(ERROR, String.format("A link lasts 1 to %d days.", FormShareLinks.MAX_DAYS));
        }
        if (request.maxSubmissions != null && (request.maxSubmissions < 1 || request.maxSubmissions > 100_000)) {
            return new ResponseDto(ERROR, "A link takes 1 to 100,000 submissions, or no cap.");
        }
        String label = request.label == null || request.label.trim().isEmpty() ? null : request.label.trim();
        if (label != null && label.length() > 200) {
            return new ResponseDto(ERROR, "A link's label is at most 200 characters.");
        }
        Map.Entry<String, FormShareLinks.Link> made = this.links.create(tenantId, form.get().formId, label, days, request.maxSubmissions,
            Boolean.TRUE.equals(request.requireSignIn), TenantContext.getAppUserId());
        Map<String, Object> view = FormShareLinks.view(made.getValue(), this.clock.instant());
        // The token itself, this once: the console builds the address from it and nobody can read it again.
        view.put("token", made.getKey());
        return new ResponseDto(SUCCESS, "Link made. Copy it now: it is not shown again.", view);
    }

    public ResponseDto revoke(Long linkId) {
        Long tenantId = FormService.workspace();
        if (tenantId == null) {
            return new ResponseDto(ERROR, FormService.NO_WORKSPACE);
        }
        if (!FormService.isAdmin()) {
            return new ResponseDto(ERROR, ADMIN_ONLY);
        }
        if (linkId == null || !this.links.revoke(tenantId, linkId, TenantContext.getAppUserId())) {
            return new ResponseDto(ERROR, "No active link " + linkId + " in this workspace.");
        }
        return new ResponseDto(SUCCESS, "Link revoked: it opens nothing now.", FormShareLinks.view(this.links.find(tenantId, linkId).get(),
            this.clock.instant()));
    }

    /** Why a form cannot be shared by link; null when it can. */
    static String shareRefusal(FormStore.Form form) {
        if (!FormStore.ACTIVE.equals(form.status)) {
            return "Only an Active form can be shared.";
        }
        if (form.fields.stream().anyMatch(f -> FormFields.LOOKUP.equals(f.getType()))) {
            return "A form that looks up another form's answers cannot be shared by link: a visitor would see those answers.";
        }
        return null;
    }
}
