package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import process.model.pojo.TaskForm;
import process.model.pojo.TaskFormField;
import process.security.TenantContext;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a form definition must satisfy before it can be saved.
 *
 * These rules exist because a bad definition does not fail here -- it produces tasks that build
 * the wrong XML and fail at run time, a long way from the person who defined the form.
 */
class TaskFormValidationTest {

    @AfterEach
    void clear() { TenantContext.clear(); }

    private TaskFormField field(String tag, String parent, String label) {
        TaskFormField f = new TaskFormField();
        f.setTagKey(tag); f.setTagParent(parent); f.setLabel(label); f.setFieldType("text");
        return f;
    }

    private TaskForm form(TaskFormField... fields) {
        TaskForm form = new TaskForm();
        form.setPipelineId("F768926");
        form.setFormName("Hurricane payload");
        form.setFields(new java.util.ArrayList<>(Arrays.asList(fields)));
        return form;
    }

    /** The rules alone: no repository, no transaction, nothing to stub. */
    private String save(TaskForm form) {
        return TaskFormServiceImpl.validate(form);
    }

    @Test
    @DisplayName("a form needs the pipeline it describes")
    void needsPipeline() {
        TaskForm f = form(field("bucket", null, "Bucket"));
        f.setPipelineId("  ");
        assertTrue(save(f).contains("pipeline"), save(f));
    }

    @Test
    @DisplayName("a form needs a name")
    void needsName() {
        TaskForm f = form(field("bucket", null, "Bucket"));
        f.setFormName("");
        assertTrue(save(f).toLowerCase().contains("name"), save(f));
    }

    @Test
    @DisplayName("a form with no fields is refused")
    void needsFields() {
        TaskForm f = form();
        assertTrue(save(f).contains("at least one field"), save(f));
    }

    @Test
    @DisplayName("every field needs the tag it writes")
    void fieldNeedsTag() {
        assertTrue(save(form(field("", null, "Bucket"))).contains("XML tag"));
    }

    @Test
    @DisplayName("every field needs a label, since that is all the reader sees")
    void fieldNeedsLabel() {
        assertTrue(save(form(field("bucket", null, " "))).contains("needs a label"));
    }

    @Test
    @DisplayName("two fields cannot write the same tag")
    void rejectsDuplicateTags() {
        String problem = save(form(
            field("bucket", null, "Bucket"),
            field("bucket", null, "Bucket again")));
        // Otherwise the second silently overwrites the first in the generated document.
        assertTrue(problem.contains("Two fields both write"), problem);
        assertTrue(problem.contains("bucket"), problem);
    }

    @Test
    @DisplayName("a field cannot nest under a tag no field creates")
    void rejectsMissingParent() {
        String problem = save(form(
            field("pipeline", null, "Root"),
            field("tables", "tenant", "Tables")));
        // The XML builder drops a child whose parent is absent, so the field would vanish.
        assertTrue(problem.contains("which no field creates"), problem);
        assertTrue(problem.contains("tenant"), problem);
    }

    @Test
    @DisplayName("a real nesting three levels deep is accepted")
    void acceptsRealNesting() {
        // The shape task 1122 actually uses.
        assertNull(save(form(
            field("pipeline", null, "Root"),
            field("target_table_config", "pipeline", "Target config"),
            field("tenant", "target_table_config", "Tenant"),
            field("tables", "tenant", "Tables"))));
    }

    @Test
    @DisplayName("a flat form with one field is accepted")
    void acceptsSimplest() {
        assertNull(save(form(field("bucket", null, "Bucket"))));
    }

    @Test
    @DisplayName("a field may name a parent defined after it, since order is presentation")
    void parentMayComeLater() {
        // Fields are ordered for the person filling them in, not for the document builder.
        assertNull(save(form(
            field("tables", "tenant", "Tables"),
            field("tenant", null, "Tenant"))));
    }
}
