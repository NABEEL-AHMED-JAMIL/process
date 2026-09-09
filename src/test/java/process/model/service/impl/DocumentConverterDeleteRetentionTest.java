package process.model.service.impl;

import org.jodconverter.core.DocumentConverter;
import org.jodconverter.core.document.DocumentFormatRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.DocumentConverterTaskDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.DocumentConverterTask;
import process.model.repository.DocumentConverterTaskRepository;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Deleting a conversion used to set Status.Delete and stop there. fetchAllTasks filters that status
 * out, so the only row that still knew where the input and output objects were left the app in the
 * same instant: the files stayed in the bucket with nothing in the product pointing at them, and the
 * only way back to them was to already know the prefix convert() had built.
 *
 * The fix is not to delete the objects. The confirmation the user accepts promises the opposite
 * ("The converted file stays in the bucket"), and the keys sit in a bucket and folder the user chose
 * themselves, so destroying them here would be irreversible data loss against that promise. What the
 * delete now does instead is say, out loud, what it kept and where -- which is what makes the
 * leftovers findable and the delete reversible. These tests pin both halves: the objects survive,
 * and the response names them.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class DocumentConverterDeleteRetentionTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;
    private static final long TASK_ID = 1042L;
    private static final String BUCKET = "etl-bucket";
    private static final String INPUT_KEY = "document-converter/1042/input/quarterly.docx";
    private static final String OUTPUT_KEY = "document-converter/1042/output/quarterly.pdf";

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
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void actAsTenant(long tenantId) {
        TenantContext.set(tenantId, "TENANT_ADMIN", 9000L, "user@tenant.example");
    }

    private DocumentConverterTask task(long tenantId, String inputStorageKey, String outputStorageKey) {
        DocumentConverterTask task = new DocumentConverterTask();
        task.setDocumentConverterTaskId(TASK_ID);
        task.setTenantId(tenantId);
        task.setTaskName("Quarterly report");
        task.setInputFileName("quarterly.docx");
        task.setInputFormat("docx");
        task.setOutputFormat("pdf");
        task.setOutputFileName("quarterly.pdf");
        task.setBucketName(BUCKET);
        task.setTargetFolder("document-converter");
        task.setInputStorageKey(inputStorageKey);
        task.setOutputStorageKey(outputStorageKey);
        task.setStatus(Status.Active);
        return task;
    }

    @Test
    void theStoredObjectsAreNeverRemovedByADelete() throws Exception {
        this.actAsTenant(TENANT_A);
        when(this.documentConverterTaskRepository.findById(TASK_ID))
            .thenReturn(Optional.of(this.task(TENANT_A, INPUT_KEY, OUTPUT_KEY)));

        ResponseDto response = this.service.deleteTask(TASK_ID);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        // Not "the delete happens to skip storage today": removing an object cannot be undone, and
        // the user was told a moment earlier that the converted file stays. Nothing about a delete
        // may reach the storage client at all.
        verifyNoInteractions(this.storageBrowserService);
    }

    @Test
    void theRowIsSoftDeletedSoTheObjectsStillHaveSomethingPointingAtThem() throws Exception {
        this.actAsTenant(TENANT_A);
        when(this.documentConverterTaskRepository.findById(TASK_ID))
            .thenReturn(Optional.of(this.task(TENANT_A, INPUT_KEY, OUTPUT_KEY)));

        this.service.deleteTask(TASK_ID);

        ArgumentCaptor<DocumentConverterTask> saved = ArgumentCaptor.forClass(DocumentConverterTask.class);
        verify(this.documentConverterTaskRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(Status.Delete);
        // The keys are what makes the delete reversible -- clearing them would strand the bytes for
        // good even though the row survives.
        assertThat(saved.getValue().getInputStorageKey()).isEqualTo(INPUT_KEY);
        assertThat(saved.getValue().getOutputStorageKey()).isEqualTo(OUTPUT_KEY);
    }

    @Test
    void theResponseSaysWhichBucketAndFolderStillHoldTheFiles() throws Exception {
        this.actAsTenant(TENANT_A);
        when(this.documentConverterTaskRepository.findById(TASK_ID))
            .thenReturn(Optional.of(this.task(TENANT_A, INPUT_KEY, OUTPUT_KEY)));

        ResponseDto response = this.service.deleteTask(TASK_ID);

        assertThat(response.getMessage()).contains("DocumentConverterTask deleted with " + TASK_ID + ".");
        assertThat(response.getMessage()).contains(BUCKET);
        // The prefix a user can paste straight into the object browser, not the two keys spelled out.
        assertThat(response.getMessage()).contains("'document-converter/1042/'");
    }

    @Test
    void theResponseCarriesTheKeysSoAClientCanOfferToCleanThemUp() throws Exception {
        this.actAsTenant(TENANT_A);
        when(this.documentConverterTaskRepository.findById(TASK_ID))
            .thenReturn(Optional.of(this.task(TENANT_A, INPUT_KEY, OUTPUT_KEY)));

        ResponseDto response = this.service.deleteTask(TASK_ID);

        DocumentConverterTaskDto retained = (DocumentConverterTaskDto) response.getData();
        assertThat(retained.getDocumentConverterTaskId()).isEqualTo(TASK_ID);
        assertThat(retained.getBucketName()).isEqualTo(BUCKET);
        assertThat(retained.getInputStorageKey()).isEqualTo(INPUT_KEY);
        assertThat(retained.getOutputStorageKey()).isEqualTo(OUTPUT_KEY);
        assertThat(retained.getStatus()).isEqualTo(Status.Delete);
    }

    @Test
    void aRowStuckOnThePendingPlaceholderNamesBothKeysRatherThanAnEmptyFolder() throws Exception {
        this.actAsTenant(TENANT_A);
        // convert() writes "pending" for both keys before it uploads. Those two share no folder, so
        // there is no prefix to point at -- saying "under ''" would send the user nowhere.
        when(this.documentConverterTaskRepository.findById(TASK_ID))
            .thenReturn(Optional.of(this.task(TENANT_A, "pending", "pending")));

        ResponseDto response = this.service.deleteTask(TASK_ID);

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        assertThat(response.getMessage()).contains("'pending' and 'pending'");
        assertThat(response.getMessage()).doesNotContain("under ''");
    }

    @Test
    void anotherTenantsTaskIsNeitherDeletedNorDescribed() throws Exception {
        this.actAsTenant(TENANT_A);
        when(this.documentConverterTaskRepository.findById(TASK_ID))
            .thenReturn(Optional.of(this.task(TENANT_B, INPUT_KEY, OUTPUT_KEY)));

        ResponseDto response = this.service.deleteTask(TASK_ID);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        // The refusal must not become a disclosure: the bucket and keys belong to the other tenant.
        assertThat(response.getMessage()).doesNotContain(BUCKET);
        assertThat(response.getData()).isNull();
        verify(this.documentConverterTaskRepository, never()).save(any(DocumentConverterTask.class));
        verifyNoInteractions(this.storageBrowserService);
    }

    @Test
    void aTaskThatIsNotThereIsStillAnErrorAndPromisesNothingAboutFiles() throws Exception {
        this.actAsTenant(TENANT_A);
        when(this.documentConverterTaskRepository.findById(TASK_ID)).thenReturn(Optional.empty());

        ResponseDto response = this.service.deleteTask(TASK_ID);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).isEqualTo("DocumentConverterTask not found with " + TASK_ID + ".");
        verify(this.documentConverterTaskRepository, never()).save(any(DocumentConverterTask.class));
        verifyNoInteractions(this.storageBrowserService);
    }

}
