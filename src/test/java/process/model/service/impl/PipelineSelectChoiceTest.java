package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.repository.PipelineRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.pojo.SourceTaskType;
import process.model.repository.TenantRepository;
import process.security.TenantContext;
import process.util.UserNameResolver;

import java.util.ArrayList;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * A dropdown's choices, which now carry a stored value AND a displayed label.
 *
 * They used to be the same string: field_options held one choice per line and the task screen
 * bound that line to an <option>'s value and its visible text at once, so an author had to pick
 * which audience to disappoint -- a token the operator cannot read, or a sentence the worker
 * cannot parse. The format grew an optional first '=' per line rather than a new column, and
 * that decision is what most of this file is defending.
 *
 * The server does not rewrite field_options and never has; it stores what it is sent. What is
 * new is that it now READS it to validate, which means its reading has to agree with the
 * browser's line for line -- a server that disagreed would reject forms the author can see are
 * fine, or accept ones the author cannot use.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class PipelineSelectChoiceTest {

    private static final long TENANT_A = 1001L;
    private static final String PIPELINE = "F768930";

    @Mock private PipelineRepository pipelineRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private UserNameResolver userNameResolver;
    @Mock private SourceTaskTypeRepository sourceTaskTypeRepository;

    private PipelineServiceImpl service;

    /** A topic with no tenant -- visible to every workspace -- so it never trips the ownership check here. */
    private static final long TOPIC_ID = 9001L;

    @BeforeEach
    void setUp() {
        this.service = new PipelineServiceImpl(this.pipelineRepository, this.tenantRepository, this.userNameResolver, this.sourceTaskTypeRepository);
        this.theTopicExists();
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private void theTopicExists() {
        SourceTaskType topic = new SourceTaskType();
        topic.setSourceTaskTypeId(TOPIC_ID);
        topic.setStatus(process.model.enums.Status.Active);
        org.mockito.Mockito.lenient().when(this.sourceTaskTypeRepository.findById(TOPIC_ID)).thenReturn(java.util.Optional.of(topic));
    }

    private PipelineField select(String options, String defaultValue) {
        PipelineField field = new PipelineField();
        field.setTagKey("format");
        field.setLabel("JSON shape");
        field.setFieldType("select");
        field.setFieldOptions(options);
        field.setDefaultValue(defaultValue);
        return field;
    }

    private Pipeline formWith(PipelineField... fields) {
        Pipeline form = new Pipeline();
        form.setSourceTaskTypeId(TOPIC_ID);
        form.setPipelineId(PIPELINE);
        form.setPipelineName("CSV to JSON demo");
        ArrayList<PipelineField> list = new ArrayList<>();
        Collections.addAll(list, fields);
        form.setFields(list);
        return form;
    }

    // ---- the rules, with no repository behind them --------------------------------------------

    @Test
    @DisplayName("a select whose choices and default line up is accepted")
    void acceptsAWellFormedSelect() {
        assertThat(PipelineServiceImpl.validate(
            formWith(select("records=JSON array\nlines=JSON Lines", "records")))).isNull();
    }

    @Test
    @DisplayName("a legacy select, one plain choice per line, is still accepted unchanged")
    void acceptsLegacyOptions() {
        // The format is a superset, not a replacement: forms written before labels existed have
        // to keep saving, or an author cannot edit a typo without first rewriting every choice.
        assertThat(PipelineServiceImpl.validate(
            formWith(select("records\nlines", "records")))).isNull();
    }

    @Test
    @DisplayName("a select with no choices is refused, naming the field")
    void refusesASelectWithNoChoices() {
        // It offers the operator nothing but "None", and when the field is also required with no
        // default, that task can never be made valid -- with "Check the highlighted fields." as
        // the only explanation anyone ever gets.
        String problem = PipelineServiceImpl.validate(formWith(select(null, null)));
        assertThat(problem).contains("JSON shape").contains("no choices");
        assertThat(PipelineServiceImpl.validate(formWith(select("   \n  ", null))))
            .contains("no choices");
    }

    @Test
    @DisplayName("two choices sharing a stored value are refused")
    void refusesDuplicateChoiceValues() {
        // The browser matches the first, so the second is unreachable however it is labelled.
        assertThat(PipelineServiceImpl.validate(
            formWith(select("records=JSON array\nrecords=Records", null)))).contains("twice");
    }

    @Test
    @DisplayName("a default that is not one of the choices is refused")
    void refusesAnUnmatchedDefault() {
        /*
         * The quiet one, and the reason this rule is on the server rather than only in the
         * dialog. The task screen seeds the control with the default, the required check passes
         * because a non-empty string is non-empty, no option matches so the dropdown paints
         * blank, and an untouched save still writes that unseen value into the payload. Rename a
         * choice without touching the default and every new task on the pipeline is wrong, with
         * nothing on any screen pointing back at the form.
         */
        String problem = PipelineServiceImpl.validate(formWith(select("records\nlines", "daily")));
        assertThat(problem).contains("daily").contains("not one of its choices");
    }

    @Test
    @DisplayName("a default matching only a LABEL is refused -- the mistake the feature creates")
    void refusesADefaultThatIsOnlyALabel() {
        // Reading the rendered dropdown and typing what it says is the obvious thing to do, and
        // it was harmless while value and label were the same string.
        assertThat(PipelineServiceImpl.validate(
            formWith(select("records=JSON array\nlines=JSON Lines", "JSON array"))))
            .contains("not one of its choices");
    }

    @Test
    @DisplayName("the comma-separated options the ETL demo seeder wrote still validate")
    void toleratesTheSeedersCommaSeparatedOptions() {
        // etl_demo_catalogue.py wrote options="records,lines" for nine select fields. Both
        // readers tolerate that shape, and they have to do it identically: if only the browser
        // did, editing a seeded form would be refused for a default the author can plainly see.
        assertThat(PipelineServiceImpl.validate(formWith(select("records,lines", "records"))))
            .isNull();
    }

    @Test
    @DisplayName("a one-line choice that legitimately contains a comma is left whole")
    void doesNotSplitARealChoiceOnItsComma() {
        // The guard that stops the tolerance above becoming a second bug: a machine-written
        // token list carries no whitespace, and a human label almost always does. So this field
        // has ONE choice, and a default of "Doe" is not it.
        assertThat(PipelineServiceImpl.validate(formWith(select("Doe, John", "Doe, John")))).isNull();
        assertThat(PipelineServiceImpl.validate(formWith(select("Doe, John", "Doe"))))
            .contains("not one of its choices");
    }

    @Test
    @DisplayName("a label may contain '=', ':' and ',' -- only the first '=' splits a line")
    void aLabelKeepsItsOwnPunctuation() {
        // No escaping is needed because everything after the first '=' is the label verbatim,
        // which is most of why the separator is positional rather than escaped.
        assertThat(PipelineServiceImpl.validate(
            formWith(select("eq=Equals (a = b), exactly\nratio=Width:Height", "eq")))).isNull();
    }

    @Test
    @DisplayName("a non-select field's options are not examined at all")
    void ignoresOptionsOnEveryOtherType() {
        // They are carried rather than dropped now -- a save made while the type reads `text`
        // used to delete them -- so a text field routinely arrives holding choices.
        PipelineField text = select("records\nlines", "anything at all");
        text.setFieldType("text");
        assertThat(PipelineServiceImpl.validate(formWith(text))).isNull();
    }

    // ---- the round trip through saveForm ------------------------------------------------------

    @Test
    @DisplayName("saveForm stores a value=label choice string byte for byte")
    void saveFormStoresTheChoicesVerbatim() {
        // The server is the format's custodian, not its editor: the task screen is the only
        // thing that has to read it, and a server that normalised it would be a second opinion
        // that drifts. This asserts the pass-through survives the copy into the new row.
        this.actAsTenant();
        String options = "records=JSON array of objects\nlines=JSON Lines (one object per line)";

        Pipeline saved = saveAndReturn(formWith(select(options, "records")));

        assertThat(saved.getFields()).hasSize(1);
        assertThat(saved.getFields().get(0).getFieldOptions()).isEqualTo(options);
        assertThat(saved.getFields().get(0).getDefaultValue()).isEqualTo("records");
    }

    @Test
    @DisplayName("saveForm stores legacy options byte for byte too")
    void saveFormStoresLegacyOptionsVerbatim() {
        this.actAsTenant();
        Pipeline saved = saveAndReturn(formWith(select("records\nlines", "lines")));
        assertThat(saved.getFields().get(0).getFieldOptions()).isEqualTo("records\nlines");
    }

    @Test
    @DisplayName("saveForm keeps the choices on a field whose type is no longer select")
    void saveFormKeepsChoicesOnANonSelectField() {
        /*
         * saveForm replaces the field list wholesale -- it clears the rows and rebuilds them,
         * with orphanRemoval deleting the originals -- so whatever the client withholds is
         * destroyed rather than merged. The client used to send null here for anything but a
         * select, which meant a save committed while a field's type happened to read `text`
         * permanently lost its choices, with the choices editor hidden the whole time. The
         * server's side of that contract is simply to store what it is given.
         */
        this.actAsTenant();
        PipelineField text = select("eq\nne\ngt", null);
        text.setFieldType("text");

        Pipeline saved = saveAndReturn(formWith(text));

        assertThat(saved.getFields().get(0).getFieldOptions()).isEqualTo("eq\nne\ngt");
    }

    @Test
    @DisplayName("saveForm refuses a broken select before it reaches the repository")
    void saveFormRefusesABrokenSelect() {
        // A business failure, not an exception: ERROR inside a 200, the way every other
        // explainable refusal in this service is reported.
        this.actAsTenant();

        ResponseDto response = this.service.saveForm(formWith(select("records\nlines", "daily")));

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).contains("not one of its choices");
        verify(this.pipelineRepository, never()).save(any());
    }

    private void actAsTenant() {
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 9000L, "user@tenant.example");
    }

    private Pipeline saveAndReturn(Pipeline submitted) {
        when(this.pipelineRepository.findAllByPipelineIdAndTenantIdAndStatusNot(
            PIPELINE, TENANT_A, Status.Delete)).thenReturn(Collections.<Pipeline>emptyList());
        when(this.pipelineRepository.save(any(Pipeline.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        ResponseDto response = this.service.saveForm(submitted);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        return (Pipeline) response.getData();
    }
}
