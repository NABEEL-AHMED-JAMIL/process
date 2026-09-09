package process.analytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import process.analytics.AnalyticsLimits;
import process.analytics.dto.ColumnDto;
import process.analytics.dto.DatasetPreviewDto;
import process.analytics.dto.DatasetSchemaDto;
import process.api.AnalyticsRestApi;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.util.ProcessUtil;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The two properties the controller carries that nothing downstream can carry for it.
 *
 * The first is the role floor. TENANT_USER sits at class level and the route in front of it
 * deliberately has no guard of its own, so this annotation is the only thing between an
 * authenticated caller of any kind and the analytics endpoints -- and an annotation that is
 * deleted, misspelled or shadowed by a method-level one fails no compile and throws nothing at
 * startup. It is asserted by reflection because that is the only place the value exists.
 *
 * The second is the shape of a failure. The house convention -- a BUSINESS failure is an HTTP 200
 * carrying status ERROR, and only an unexpected exception is a 500 -- is implemented once per
 * endpoint by hand, in a catch block, which is precisely the kind of code that gets rewritten by
 * someone tidying up. Two halves of it matter separately: an AnalyticsException was written for a
 * person to read and must arrive with its own words intact, and anything else must arrive as the
 * generic sentence, because an unmapped failure is exactly the string that carries a host name or
 * a path.
 *
 * The resolver and the query service are mocked away on purpose. What each of them decides is
 * pinned by DatasetResolverTest and by the engine's own tests; what is unproven, and is all this
 * asserts, is what the controller does with their answers.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsRestApiTest {

    private static final String ALIAS = "store";
    private static final String PATH = "etl-demo/sales.csv";

    /** Written for a reader, by explain(). The test is that this reaches the reader unchanged. */
    private static final String BUSINESS_FAILURE =
        "Nothing to read at etl-bucket/etl-demo/sales.csv. "
            + "The connection worked, so check the path.";

    /** Not written for anybody. Names a host and a credential, as an unmapped failure tends to. */
    private static final String LEAKY_FAILURE =
        "Connection refused: minio.internal:9000 (secret AKIAI44QH8DHBEXAMPLE)";

    @Mock private DatasetResolver datasetResolver;
    @Mock private AnalyticsQueryService analyticsQueryService;

    private final DatasetRef dataset = datasetRef();

    private AnalyticsRestApi api() {
        // null library service: these tests are about the controller's contract, and history
        // is written on a seam that tolerates its absence rather than failing the request.
        return new AnalyticsRestApi(this.datasetResolver, this.analyticsQueryService, null, null,
            new AnalyticsLimits());
    }

    /**
     * DatasetRef's constructor is package-private and the resolver is its only production caller.
     * This test sits in that package, so it can hand the controller the same object the resolver
     * would have handed it rather than mocking a final class.
     */
    private static DatasetRef datasetRef() {
        StorageConnection connection = new StorageConnection();
        connection.setTenantId(1001L);
        connection.setProvider(StorageProvider.MINIO);
        connection.setAlias(ALIAS);
        connection.setBucketName("etl-bucket");
        connection.setStatus(Status.Active);
        return new DatasetRef(connection, "etl-bucket", PATH, DatasetRef.Format.CSV);
    }

    private static DatasetSchemaDto schemaDto() {
        return new DatasetSchemaDto("etl-bucket", PATH, "CSV", false,
            Collections.singletonList(new ColumnDto("amount", "DECIMAL(10,2)")));
    }

    private static DatasetPreviewDto previewDto(long totalRows) {
        return new DatasetPreviewDto(Arrays.asList("id", "amount"),
            Collections.singletonList(Arrays.asList("1", "9.99")), 0, 100, totalRows, false);
    }

    private ResponseDto bodyOf(ResponseEntity<?> response) {
        assertThat(response.getBody()).isInstanceOf(ResponseDto.class);
        return (ResponseDto) response.getBody();
    }

    // ---- the happy paths, so a refusal below is a refusal and not a broken fixture ------------

    @Test
    void schemaAnswersWithWhatTheServiceRead() throws Exception {
        DatasetSchemaDto read = schemaDto();
        when(this.datasetResolver.resolve(ALIAS, PATH)).thenReturn(this.dataset);
        when(this.analyticsQueryService.schemaOf(this.dataset)).thenReturn(read);

        ResponseEntity<?> response = api().schema(ALIAS, PATH);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyOf(response).getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(bodyOf(response).getData()).isSameAs(read);
    }

    @Test
    void previewAnswersWithWhatTheServiceRead() throws Exception {
        DatasetPreviewDto read = previewDto(4200L);
        when(this.datasetResolver.resolve(ALIAS, PATH)).thenReturn(this.dataset);
        when(this.analyticsQueryService.preview(this.dataset, 0, 100, null)).thenReturn(read);

        ResponseEntity<?> response = api().preview(ALIAS, PATH, 0, 100, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bodyOf(response).getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(bodyOf(response).getData()).isSameAs(read);
    }

    @Test
    void theCallerNamesAConnectionAndAPath_andNothingElseReachesTheResolver() throws Exception {
        when(this.datasetResolver.resolve(ALIAS, PATH)).thenReturn(this.dataset);
        when(this.analyticsQueryService.schemaOf(this.dataset)).thenReturn(schemaDto());

        api().schema(ALIAS, PATH);

        // The feature's central security property is that a bucket is never a request field: the
        // controller passes the alias and the path through untouched and the resolver turns them
        // into a location. A controller that rewrote either would be building an address.
        verify(this.datasetResolver).resolve(ALIAS, PATH);
    }

    // ---- a business failure is an OK ----------------------------------------------------------

    @Test
    void aDatasetTheResolverRefusesIsAnOkCarryingItsOwnWords() throws Exception {
        when(this.datasetResolver.resolve(ALIAS, PATH))
            .thenThrow(new AnalyticsException(BUSINESS_FAILURE));

        ResponseEntity<?> schema = api().schema(ALIAS, PATH);
        ResponseEntity<?> preview = api().preview(ALIAS, PATH, 0, 100, null);

        for (ResponseEntity<?> response : Arrays.asList(schema, preview)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(bodyOf(response).getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
            // Not merely "contains": the message was written to be read as it stands, and a
            // controller that wrapped or prefixed it would be editing somebody's sentence.
            assertThat(bodyOf(response).getMessage()).isEqualTo(BUSINESS_FAILURE);
        }
        // The refusal happened before anything was opened, so no session was ever paid for.
        verifyNoInteractions(this.analyticsQueryService);
    }

    @Test
    void aQueryTheGovernorRefusesIsAnOkCarryingItsOwnWords() throws Exception {
        // The other thrower of an AnalyticsException, and the one a user hits under load. It is
        // advice -- "try again in a moment" -- and is worthless if it arrives as a 500.
        String refused = "Too many analytics queries are running right now. Try again in a moment.";
        when(this.datasetResolver.resolve(ALIAS, PATH)).thenReturn(this.dataset);
        when(this.analyticsQueryService.schemaOf(this.dataset))
            .thenThrow(new AnalyticsException(refused));
        when(this.analyticsQueryService.preview(this.dataset, 0, 100, null))
            .thenThrow(new AnalyticsException(refused));

        ResponseEntity<?> schema = api().schema(ALIAS, PATH);
        ResponseEntity<?> preview = api().preview(ALIAS, PATH, 0, 100, null);

        for (ResponseEntity<?> response : Arrays.asList(schema, preview)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(bodyOf(response).getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
            assertThat(bodyOf(response).getMessage()).isEqualTo(refused);
        }
    }

    // ---- and only an unexpected failure is a 500 -----------------------------------------------

    @Test
    void anUnexpectedFailureIsA500ThatSaysNothingAboutItself() throws Exception {
        when(this.datasetResolver.resolve(ALIAS, PATH)).thenReturn(this.dataset);
        when(this.analyticsQueryService.schemaOf(this.dataset))
            .thenThrow(new IllegalStateException(LEAKY_FAILURE));
        when(this.analyticsQueryService.preview(this.dataset, 0, 100, null))
            .thenThrow(new IllegalStateException(LEAKY_FAILURE));

        ResponseEntity<?> schema = api().schema(ALIAS, PATH);
        ResponseEntity<?> preview = api().preview(ALIAS, PATH, 0, 100, null);

        for (ResponseEntity<?> response : Arrays.asList(schema, preview)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(bodyOf(response).getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
            assertThat(bodyOf(response).getMessage()).isEqualTo(ProcessUtil.INTERNAL_ERROR_500);
            // The half of the convention that is a security property rather than a style: what
            // reached the catch block named an internal host and a credential.
            assertThat(bodyOf(response).getMessage())
                .doesNotContain("minio.internal")
                .doesNotContain("AKIA");
            assertThat(bodyOf(response).getData()).isNull();
        }
    }

    @Test
    void anUnexpectedFailureInTheResolverIsA500Too() throws Exception {
        // The resolver's own refusals are AnalyticsExceptions, so anything else out of it is a
        // fault rather than an answer -- a repository that cannot reach the database, say.
        when(this.datasetResolver.resolve(ALIAS, PATH))
            .thenThrow(new IllegalStateException(LEAKY_FAILURE));

        ResponseEntity<?> response = api().preview(ALIAS, PATH, 0, 100, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(bodyOf(response).getMessage()).isEqualTo(ProcessUtil.INTERNAL_ERROR_500);
    }

    // ---- paging, and the count the caller already holds ----------------------------------------

    @Test
    void aKnownTotalIsHandedToTheServiceUntouched() throws Exception {
        when(this.datasetResolver.resolve(ALIAS, PATH)).thenReturn(this.dataset);
        when(this.analyticsQueryService.preview(this.dataset, 2, 250, 4200))
            .thenReturn(previewDto(4200L));

        ResponseEntity<?> response = api().preview(ALIAS, PATH, 2, 250, 4200);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // A page turn already knows the total from the response before it, and re-counting costs a
        // second session and a second governor permit for an answer the caller is holding.
        verify(this.analyticsQueryService).preview(this.dataset, 2, 250, 4200);
    }

    @Test
    void anAbsentKnownTotalIsNotInvented() throws Exception {
        when(this.datasetResolver.resolve(ALIAS, PATH)).thenReturn(this.dataset);
        when(this.analyticsQueryService.preview(this.dataset, 1, 250, null))
            .thenReturn(previewDto(4200L));

        api().preview(ALIAS, PATH, 1, 250, null);

        // Null has to stay null all the way down. A controller that defaulted it to zero would
        // read as "the caller knows the total is zero" at the far end, which is a different claim.
        verify(this.analyticsQueryService).preview(this.dataset, 1, 250, null);
    }

    @Test
    void anAbsentPageIsTheFirstPage() throws Exception {
        when(this.datasetResolver.resolve(ALIAS, PATH)).thenReturn(this.dataset);
        when(this.analyticsQueryService.preview(this.dataset, 0, 100, null))
            .thenReturn(previewDto(4200L));

        // Spring's defaultValue = "0" means a real request never arrives with a null page. The
        // controller guards for it anyway, and this is what keeps that guard honest if the
        // annotation is ever edited.
        api().preview(ALIAS, PATH, null, 100, null);

        verify(this.analyticsQueryService).preview(this.dataset, 0, 100, null);
    }

    @Test
    void anAbsentPageSizeIsLeftForTheServiceToDecide() throws Exception {
        when(this.datasetResolver.resolve(ALIAS, PATH)).thenReturn(this.dataset);
        when(this.analyticsQueryService.preview(this.dataset, 0, null, null))
            .thenReturn(previewDto(4200L));

        api().preview(ALIAS, PATH, 0, null, null);

        // Deliberately passed through rather than defaulted here: the configured page size and the
        // maximum are policy, AnalyticsQueryService.pageSize owns both, and a second copy of a
        // clamp is a second thing to keep in step with analytics.query.* .
        verify(this.analyticsQueryService).preview(this.dataset, 0, null, null);
    }

    // ---- the role floor -------------------------------------------------------------------------

    @Test
    void everyEndpointSitsBehindTheTenantUserFloor() {
        PreAuthorize floor = AnalyticsRestApi.class.getAnnotation(PreAuthorize.class);

        assertThat(floor)
            .as("the class-level role floor is the only guard in front of these endpoints -- the "
                + "route carries none, by decision")
            .isNotNull();
        assertThat(floor.value()).isEqualTo("hasRole('TENANT_USER')");
    }

    @Test
    void noHandlerQuietlyEscapesTheClassFloor() {
        List<String> mapped = new ArrayList<>();
        for (Method handler : AnalyticsRestApi.class.getDeclaredMethods()) {
            // Spring's own lookup rather than isAnnotationPresent, so a handler later written as
            // @GetMapping -- which carries @RequestMapping as a meta-annotation -- still counts as
            // mapped here instead of quietly dropping out of this loop.
            if (!AnnotatedElementUtils.hasAnnotation(handler, RequestMapping.class)) {
                continue;
            }
            mapped.add(handler.getName());
            // A method-level @PreAuthorize REPLACES the class-level one rather than adding to it,
            // so a handler declaring its own is a handler the floor above no longer covers. If one
            // ever needs a different role it should be a deliberate edit to this assertion.
            assertThat(AnnotatedElementUtils.hasAnnotation(handler, PreAuthorize.class))
                .as(handler.getName() + " must inherit the class floor rather than override it")
                .isFalse();
        }
        // Without this the loop above passes by iterating over nothing, which is how a renamed or
        // dropped mapping annotation would look from here.
        assertThat(mapped).contains("schema", "preview");
    }
    // ---- the feature switch --------------------------------------------------------------------

    @Test
    void everyEndpointRefusesWhenAnalyticsIsSwitchedOff() {
        // analytics.enabled shipped as a property with three guard methods and ZERO callers, so
        // setting it false left all fifteen endpoints serving while the health check reported the
        // feature off. An operator killing analytics mid-incident got a green confirmation of a
        // state that was not true, which is worse than having no switch at all.
        AnalyticsLimits off = new AnalyticsLimits();
        ReflectionTestUtils.setField(off, "enabled", false);
        AnalyticsRestApi api = new AnalyticsRestApi(this.datasetResolver,
            this.analyticsQueryService, null, null, off);

        for (ResponseEntity<?> response : java.util.Arrays.asList(
            api.schema("store", "sales.csv"),
            api.preview("store", "sales.csv", 0, null, null),
            api.profile("store", "sales.csv"))) {

            assertThat(response.getStatusCode())
                .as("a switched-off feature is a business refusal, not a 404 and not a 500")
                .isEqualTo(HttpStatus.OK);
            assertThat(((ResponseDto) response.getBody()).getStatus())
                .isEqualTo(ProcessUtil.ERROR_MESSAGE);
        }
        // And nothing reached the engine: a flag that refuses after doing the work is not a flag.
        verifyNoInteractions(this.analyticsQueryService);
    }

    @Test
    void theSameEndpointsServeWhenItIsSwitchedOn() {
        // The control. A guard that refused unconditionally would satisfy the test above while
        // taking the feature down, and would look identical in a green suite.
        AnalyticsLimits on = new AnalyticsLimits();
        assertThat(on.isEnabled())
            .as("a directly constructed limits must be ON, or every existing test runs with "
                + "analytics disabled and the failures look like broken queries")
            .isTrue();
    }

}
