package process.model.service.impl;

import org.jodconverter.core.DocumentConverter;
import org.jodconverter.core.document.DocumentFamily;
import org.jodconverter.core.document.DocumentFormat;
import org.jodconverter.core.document.DocumentFormatRegistry;
import org.jodconverter.core.job.ConversionJob;
import org.jodconverter.core.job.ConversionJobWithOptionalSourceFormatUnspecified;
import org.jodconverter.core.job.ConversionJobWithRequiredTargetFormatUnspecified;
import org.jodconverter.core.job.ConversionJobWithSourceSpecified;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import process.model.pojo.DocumentConverterTask;
import process.model.repository.DocumentConverterTaskRepository;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where the transaction boundary sits around a conversion.
 *
 * convert() spends almost all of its time outside the database: the LibreOffice round trip runs out
 * of process and is allowed, by jodconverter.local's own queue and execution timeouts, to sit there
 * for the better part of four minutes, and the two uploads after it are network round trips carrying
 * the whole file. A method-wide @Transactional pinned a pooled connection for all of that while
 * issuing no statements on it, so with HikariCP's default pool of ten, ten people converting at once
 * left nothing for the job list, the dashboard or login -- one slow document taking the rest of the
 * application down with it.
 *
 * Taking the transaction off means a failed upload no longer rolls the row back, so convert() has to
 * undo it itself. Both halves are pinned here, because the second is what makes the first safe.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class DocumentConverterTransactionBoundaryTest {

    private static final long TENANT_A = 1001L;
    private static final long TASK_ID = 1042L;
    private static final String BUCKET = "etl-bucket";
    private static final String DOCX_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Mock private DocumentConverterTaskRepository documentConverterTaskRepository;
    @Mock private StorageBrowserService storageBrowserService;
    @Mock private TenantFilterHelper tenantFilterHelper;
    @Mock private DocumentConverter documentConverter;
    @Mock private DocumentFormatRegistry documentFormatRegistry;

    private DocumentConverterServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new DocumentConverterServiceImpl(this.documentConverterTaskRepository,
            this.storageBrowserService, this.tenantFilterHelper, this.documentConverter, this.documentFormatRegistry);
        // @Value is not applied when the service is built by hand, and a limit of zero would refuse
        // every upload before any of this is reached.
        ReflectionTestUtils.setField(this.service, "maxFileSizeMb", 50);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private Method convertMethod() throws Exception {
        return DocumentConverterServiceImpl.class.getMethod("convert", MultipartFile.class,
            String.class, String.class, String.class, String.class, boolean.class);
    }

    @Test
    void theConversionItselfIsNotWrappedInATransaction() throws Exception {
        // A class-level @Transactional would put one back on every method, convert() included.
        assertThat(DocumentConverterServiceImpl.class.getAnnotation(Transactional.class)).isNull();
        assertThat(this.convertMethod().getAnnotation(Transactional.class))
            .as("convert() pins a pooled connection for the whole LibreOffice round trip if it is transactional")
            .isNull();
    }

    @Test
    void theShortDatabaseOnlyMethodsKeepTheirTransactions() throws Exception {
        // The point is not "no transactions in this service" -- it is that the boundary sits around
        // the work that only talks to the database, which is where it can be held cheaply.
        assertThat(DocumentConverterServiceImpl.class
            .getMethod("fetchAllTasks").getAnnotation(Transactional.class)).isNotNull();
        assertThat(DocumentConverterServiceImpl.class
            .getMethod("fetchTaskById", Long.class).getAnnotation(Transactional.class)).isNotNull();
        assertThat(DocumentConverterServiceImpl.class
            .getMethod("deleteTask", Long.class).getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void aFailedUploadRemovesThePlaceholderRowNothingElseWouldRollBackNow() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 9000L, "user@tenant.example");
        this.stubASuccessfulConversion();
        when(this.documentConverterTaskRepository.save(any(DocumentConverterTask.class)))
            .thenAnswer(invocation -> {
                DocumentConverterTask saving = invocation.getArgument(0);
                saving.setDocumentConverterTaskId(TASK_ID);
                return saving;
            });
        doThrow(new IllegalStateException("MinIO refused the upload"))
            .when(this.storageBrowserService)
            .uploadObject(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());

        MockMultipartFile file = new MockMultipartFile("file", "quarterly.docx",
            DOCX_CONTENT_TYPE, "report body".getBytes());

        assertThatThrownBy(() -> this.service.convert(file, "pdf", BUCKET, "document-converter", "Quarterly", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("MinIO refused the upload");

        // Both storage keys still read "pending" on that row. Left behind, it lists as a finished
        // conversion whose output cannot be downloaded and that nothing ever goes back to finish --
        // the method-wide transaction used to roll it away, and now this has to remove it.
        verify(this.documentConverterTaskRepository).delete(any(DocumentConverterTask.class));
    }

    /**
     * The jodconverter fluent chain, stubbed link by link, doing nothing when it finally runs.
     * DocumentFormat is final, so the formats are built rather than mocked.
     */
    private void stubASuccessfulConversion() {
        DocumentFormat format = DocumentFormat.builder()
            .name("Portable Document Format")
            .extension("pdf")
            .mediaType("application/pdf")
            .inputFamily(DocumentFamily.TEXT)
            .build();
        ConversionJobWithOptionalSourceFormatUnspecified sourceUnspecified =
            mock(ConversionJobWithOptionalSourceFormatUnspecified.class);
        ConversionJobWithSourceSpecified sourceSpecified = mock(ConversionJobWithSourceSpecified.class);
        ConversionJobWithRequiredTargetFormatUnspecified targetUnspecified =
            mock(ConversionJobWithRequiredTargetFormatUnspecified.class);
        ConversionJob job = mock(ConversionJob.class);

        when(this.documentFormatRegistry.getFormatByExtension(anyString())).thenReturn(format);
        when(this.documentConverter.convert(any(File.class))).thenReturn(sourceUnspecified);
        when(sourceUnspecified.as(any(DocumentFormat.class))).thenReturn(sourceSpecified);
        when(sourceSpecified.to(any(OutputStream.class))).thenReturn(targetUnspecified);
        when(targetUnspecified.as(any(DocumentFormat.class))).thenReturn(job);
    }
}
