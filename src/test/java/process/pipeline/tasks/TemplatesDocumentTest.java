package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.RunDocuments;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-364: a run an approved document started fills {{document.<field>}} from that document -- read only when a template
 * names one -- and {{document_count}} / {{document_ids}} from the run's documents.
 */
class TemplatesDocumentTest {

    @Test
    void aDocumentsValuesFillItsPlaceholdersAndAreReadOnlyWhenNamed() {
        TaskContext context = new TaskContext(Collections.emptyMap(), null);
        context.documents = Arrays.asList(new RunDocuments.Document(1180L, 1007L, 2), new RunDocuments.Document(1181L, 1007L, 2));
        context.documentValues.put("invoice_number", "INV-NW-1005");
        context.documentValues.put("total", 1250.5);
        context.documentValues.put("file_name", "INV-NW-1005.pdf");

        Map<String, Object> values = Templates.ofRun(context, null);
        assertThat(Templates.fill("run {{run}}: {{document_count}} document(s) {{document_ids}}", values))
            .isEqualTo("run 88001: 2 document(s) 1180,1181");
        assertThat(context.documentReads).as("nothing named a document's value").isZero();

        assertThat(Templates.fill("Invoice {{document.invoice_number}} ({{ document.file_name }}) for {{document.total}}, id {{document.id}}",
            values)).isEqualTo("Invoice INV-NW-1005 (INV-NW-1005.pdf) for 1250.5, id 1180");
        assertThat(Templates.fill("{{document.total}} {{document.po_nmber}}", values)).isEqualTo("1250.5 {{document.po_nmber}}");
        assertThat(context.documentReads).as("read once for the map").isEqualTo(1);
    }

    @Test
    void aRunNoDocumentStartedSaysNothingWhereADocumentWouldBe() {
        TaskContext context = new TaskContext(Collections.emptyMap(), null);
        Map<String, Object> values = Templates.ofRun(context, null);
        assertThat(Templates.fill("Invoice [{{document.invoice_number}}] run {{run}} {{documnet.total}}", values))
            .isEqualTo("Invoice [] run 88001 {{documnet.total}}");
        assertThat(context.documentReads).isZero();
    }

    /** UX review 2026-10-12 (MIG-364 leftover): a run started by hand counts 0 documents and names none. */
    @Test
    void aRunStartedByHandCountsNoDocuments() {
        TaskContext context = new TaskContext(Collections.emptyMap(), null);
        Map<String, Object> values = Templates.ofRun(context, null);
        assertThat(Templates.fill("AP notice: {{document_count}} approved invoice(s) [{{document_ids}}] in run {{run}}.", values))
            .isEqualTo("AP notice: 0 approved invoice(s) [] in run 88001.");
        assertThat(context.documentReads).isZero();
    }
}
