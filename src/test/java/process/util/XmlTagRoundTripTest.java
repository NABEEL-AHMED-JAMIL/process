package process.util;

import org.junit.jupiter.api.Test;
import process.model.dto.ConfigurationMakerRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a task payload read back as tags says the same thing the XML does.
 *
 * <b>A task carries its configuration twice</b> -- as the XML in {@code source_task.task_payload},
 * which is what the worker runs, and as {@code source_task_payload} rows, which are what the
 * editor renders. Nothing kept the two in step. A caller that supplied only the XML (the API, a
 * hand-written payload) created a task that RAN correctly and opened in the editor with every
 * field blank; an update that changed the XML without resending tags left the tags describing the
 * previous payload. Either way the editor showed a configuration the task did not have, and
 * saving from that screen wrote it back over the one that worked.
 *
 * The XML is now the source of truth and the rows are derived from it. These tests are what fails
 * if the derivation stops being faithful.
 *
 * @author Nabeel Ahmed
 */
public class XmlTagRoundTripTest {

    private static final String PAYLOAD =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
        + "<pipeline>\n"
        + "  <input_folder>scans/in</input_folder>\n"
        + "  <output_folder>scans/out</output_folder>\n"
        + "  <max_images>20</max_images>\n"
        + "</pipeline>";

    /**
     * The value of a tag, where absent and present-but-null are different answers.
     *
     * findFirst() on a mapped stream cannot express the second: it throws NullPointerException on
     * a null element, and a branch tag legitimately has no value -- which is what one of these
     * tests is about. So the tag is found first and read after.
     */
    private static String valueOf(List<ConfigurationMakerRequest.TagInfo> tags, String key) {
        return tags.stream()
            .filter(t -> key.equals(t.getTagKey()))
            .findFirst()
            .map(ConfigurationMakerRequest.TagInfo::getTagValue)
            .orElse(null);
    }

    @Test
    void everyLeafBecomesATagCarryingItsValue() {
        List<ConfigurationMakerRequest.TagInfo> tags = XmlOutTagInfoUtil.parseXmlToTags(PAYLOAD);

        assertThat(valueOf(tags, "input_folder")).isEqualTo("scans/in");
        assertThat(valueOf(tags, "output_folder")).isEqualTo("scans/out");
        assertThat(valueOf(tags, "max_images")).isEqualTo("20");
    }

    @Test
    void theRootIsCarriedAsTheParentOfEveryField() {
        // The editor keys a control on parent plus tag, because two fields may share a tag name
        // under different parents. A derivation that dropped the parent would collide them.
        List<ConfigurationMakerRequest.TagInfo> tags = XmlOutTagInfoUtil.parseXmlToTags(PAYLOAD);

        assertThat(tags.stream()
            .filter(t -> "input_folder".equals(t.getTagKey()))
            .findFirst().get().getTagParent()).isEqualTo("pipeline");
    }

    @Test
    void aBranchCarriesNoValueOfItsOwn() {
        // Only leaves hold values, matching what makeXml writes. A branch given the concatenated
        // text of its children would put that whole blob in a form field.
        List<ConfigurationMakerRequest.TagInfo> tags = XmlOutTagInfoUtil.parseXmlToTags(PAYLOAD);

        assertThat(valueOf(tags, "pipeline")).isNull();
    }

    @Test
    void nestedElementsKeepTheirOwnParent() {
        String nested = "<pipeline><source><bucket>etl-bucket</bucket></source></pipeline>";

        List<ConfigurationMakerRequest.TagInfo> tags = XmlOutTagInfoUtil.parseXmlToTags(nested);

        assertThat(tags.stream()
            .filter(t -> "bucket".equals(t.getTagKey()))
            .findFirst().get().getTagParent()).isEqualTo("source");
    }

    @Test
    void indentationIsNotMistakenForAValue() {
        // XmlOutTagInfoUtil indents what it writes, so a value arrives surrounded by newlines.
        // Unstripped, every field in the editor would open with whitespace in front of it.
        List<ConfigurationMakerRequest.TagInfo> tags = XmlOutTagInfoUtil.parseXmlToTags(PAYLOAD);

        assertThat(valueOf(tags, "input_folder")).doesNotContain("\n").isEqualTo("scans/in");
    }

    @Test
    void anEmptyTagReadsAsEmptyRatherThanNull() {
        List<ConfigurationMakerRequest.TagInfo> tags =
            XmlOutTagInfoUtil.parseXmlToTags("<pipeline><suffix></suffix></pipeline>");

        assertThat(valueOf(tags, "suffix")).isEmpty();
    }

    @Test
    void aPayloadThatIsNotXmlOpensEmptyRatherThanFailing() {
        // A task can be saved with a payload that is not yet valid. Refusing to load its editor
        // would leave the only screen that can repair it unreachable.
        assertThat(XmlOutTagInfoUtil.parseXmlToTags("not xml at all")).isEmpty();
        assertThat(XmlOutTagInfoUtil.parseXmlToTags("<pipeline><unclosed>")).isEmpty();
    }

    @Test
    void nothingIsReadAsNothing() {
        assertThat(XmlOutTagInfoUtil.parseXmlToTags(null)).isEmpty();
        assertThat(XmlOutTagInfoUtil.parseXmlToTags("")).isEmpty();
        assertThat(XmlOutTagInfoUtil.parseXmlToTags("   ")).isEmpty();
    }

    @Test
    void anExternalEntityInAPayloadIsRefusedRatherThanResolved() {
        // Task payloads are operator-supplied text. Left open, a DOCTYPE in one reads local files
        // or opens connections from inside the application -- the caller supplies the payload and
        // then reads the result back through the editor, which is the whole of XXE.
        String hostile =
            "<?xml version=\"1.0\"?>\n"
            + "<!DOCTYPE pipeline [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]>\n"
            + "<pipeline><input_folder>&xxe;</input_folder></pipeline>";

        List<ConfigurationMakerRequest.TagInfo> tags = XmlOutTagInfoUtil.parseXmlToTags(hostile);

        // The doctype is refused outright, so the payload yields nothing at all.
        assertThat(tags).isEmpty();
    }

    @Test
    void aRealisticPayloadRoundTripsEveryFieldTheFormWouldShow() {
        List<ConfigurationMakerRequest.TagInfo> tags = XmlOutTagInfoUtil.parseXmlToTags(PAYLOAD);

        // Three leaves plus the root. A count that drifts means the editor gains or loses a row.
        assertThat(tags).hasSize(4);
        assertThat(tags.stream().map(ConfigurationMakerRequest.TagInfo::getTagKey))
            .containsExactlyInAnyOrder("pipeline", "input_folder", "output_folder", "max_images");
    }
}
