package process.model.service.impl;

import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.FileUploadDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceTaskDto;
import process.model.dto.SourceTaskTypeDto;
import process.model.enums.Status;
import process.model.pojo.PipelineConfig;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.repository.PipelineConfigRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.notifications.TestNotifications;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.settings.TaskConfigRules;
import process.util.ProcessUtil;
import process.util.TaskPayloadLocationUtil;
import process.util.excel.BulkExcel;

import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-167: every way a task is saved -- one at a time, edited, or a spreadsheet of them -- holds its payload to the
 * configuration rules: a credential-named tag holds ${secret:KEY} and never the credential, and every reference names
 * an entry of the task's OWN workspace, of the kind it is referenced as.
 */
class SourceTaskConfigRuleTest {

    private static final long MINE = 2905L;
    private static final long THEIRS = 2901L;

    private final SourceTaskRepository tasks = mock(SourceTaskRepository.class);
    private final SourceTaskTypeRepository types = mock(SourceTaskTypeRepository.class);
    private final PipelineConfigRepository entries = mock(PipelineConfigRepository.class);
    private SourceTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new SourceTaskServiceImpl(new BulkExcel(), null, null, this.tasks, this.types, mock(TenantFilterHelper.class),
            new TaskPayloadLocationUtil(), null, TestNotifications.recording(null, null, null), null);
        ReflectionTestUtils.setField(this.service, "taskConfigRules", new TaskConfigRules(this.entries));
        TenantContext.set(MINE, "TENANT_ADMIN", 42L, "ops@medaxis.test");
        SourceTaskType type = new SourceTaskType();
        type.setSourceTaskTypeId(7300L);
        type.setTenantId(MINE);
        type.setStatus(Status.Active);
        when(this.types.findSourceTaskTypeBySourceTaskTypeIdAndStatus(7300L, Status.Active)).thenReturn(Optional.of(type));
        when(this.types.findById(7300L)).thenReturn(Optional.of(type));
        when(this.entries.findByTenantIdAndConfigKey(anyLong(), anyString())).thenReturn(Optional.empty());
        when(this.entries.findByTenantIdAndConfigKey(MINE, "DB_PASSWORD")).thenReturn(Optional.of(entry(MINE, "DB_PASSWORD", "SECRET")));
        when(this.entries.findByTenantIdAndConfigKey(MINE, "INPUT_BUCKET")).thenReturn(Optional.of(entry(MINE, "INPUT_BUCKET", "VALUE")));
        when(this.entries.findByTenantIdAndConfigKey(THEIRS, "API_TOKEN")).thenReturn(Optional.of(entry(THEIRS, "API_TOKEN", "SECRET")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void referencesToTheWorkspacesOwnEntriesAreSaved() throws Exception {
        ResponseDto saved = this.service.addSourceTask(task(
            "<pipeline><bucket>${config:INPUT_BUCKET}</bucket><db_password>${secret:DB_PASSWORD}</db_password></pipeline>"));

        assertThat(saved.getStatus()).as(saved.getMessage()).isEqualTo("SUCCESS");
    }

    @Test
    void aLiteralPasswordIsRefusedOnEverySavePath() throws Exception {
        String payload = "<pipeline><db_password>hunter2-canary</db_password></pipeline>";

        ResponseDto added = this.service.addSourceTask(task(payload));
        ResponseDto updated = this.service.updateSourceTask(this.existing(payload));
        ResponseDto uploaded = this.service.uploadSourceTask(upload(payload));

        for (ResponseDto refused : new ResponseDto[] {added, updated}) {
            assertThat(refused.getStatus()).isEqualTo("ERROR");
            assertThat(refused.getMessage()).contains("<db_password>").contains("${secret:KEY}").doesNotContain("hunter2-canary");
        }
        assertThat(uploaded.getStatus()).isEqualTo("ERROR");
        assertThat(String.valueOf(uploaded.getData())).contains("<db_password>").contains("row 2").doesNotContain("hunter2-canary");
        verify(this.tasks, never()).save(any());
    }

    @Test
    void anotherWorkspacesKeyIsRefusedOnEverySavePath() throws Exception {
        String payload = "<pipeline><api_token>${secret:API_TOKEN}</api_token></pipeline>";

        ResponseDto added = this.service.addSourceTask(task(payload));
        ResponseDto updated = this.service.updateSourceTask(this.existing(payload));
        ResponseDto uploaded = this.service.uploadSourceTask(upload(payload));

        assertThat(added.getMessage()).isEqualTo("The task references ${secret:API_TOKEN}, which is not a secret of this workspace. "
            + "Add it in Configuration values first.");
        assertThat(updated.getMessage()).isEqualTo(added.getMessage());
        assertThat(uploaded.getStatus()).isEqualTo("ERROR");
        assertThat(String.valueOf(uploaded.getData())).contains("${secret:API_TOKEN}").contains("row 2");
        verify(this.tasks, never()).save(any());
    }

    @Test
    void aReferenceOfTheWrongKindIsRefused() throws Exception {
        assertThat(this.service.addSourceTask(task("<p><bucket>${secret:INPUT_BUCKET}</bucket></p>")).getMessage())
            .contains("not a secret of this workspace");
        assertThat(this.service.addSourceTask(task("<p><note>${config:DB_PASSWORD}</note></p>")).getMessage())
            .contains("not a value of this workspace");
        assertThat(this.service.addSourceTask(task("<p><note>${config:lower}</note></p>")).getMessage()).contains("UPPER_SNAKE");
        verify(this.tasks, never()).save(any());
    }

    /** A platform admin saving for a workspace is held to THAT workspace's entries. */
    @Test
    void aPlatformAdminsTaskIsHeldToTheWorkspaceItIsFor() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        SourceTask theirs = new SourceTask();
        theirs.setTaskDetailId(7302L);
        theirs.setTenantId(THEIRS);
        theirs.setTaskStatus(Status.Active);
        when(this.tasks.findById(7302L)).thenReturn(Optional.of(theirs));
        SourceTaskDto update = task("<p><db_password>${secret:DB_PASSWORD}</db_password></p>");
        update.setTaskDetailId(7302L);

        assertThat(this.service.updateSourceTask(update).getMessage()).contains("${secret:DB_PASSWORD}");
        verify(this.tasks, never()).save(any());
    }

    private SourceTaskDto existing(String payload) {
        SourceTask existing = new SourceTask();
        existing.setTaskDetailId(7301L);
        existing.setTenantId(MINE);
        existing.setTaskStatus(Status.Active);
        when(this.tasks.findById(7301L)).thenReturn(Optional.of(existing));
        SourceTaskDto update = task(payload);
        update.setTaskDetailId(7301L);
        return update;
    }

    private static SourceTaskDto task(String payload) {
        SourceTaskTypeDto type = new SourceTaskTypeDto();
        type.setSourceTaskTypeId(7300L);
        SourceTaskDto task = new SourceTaskDto();
        task.setTaskName("nightly export");
        task.setTaskPayload(payload);
        task.setSourceTaskType(type);
        return task;
    }

    private static FileUploadDto upload(String payload) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("ListSourceTask");
            String[] header = {"TaskTypeId", "Task Name", "Task Payload", "PipelineId", "HomePage"};
            XSSFRow first = sheet.createRow(0);
            for (int i = 0; i < header.length; i++) {
                first.createCell(i).setCellValue(header[i]);
            }
            XSSFRow row = sheet.createRow(1);
            row.createCell(0).setCellValue("7300");
            row.createCell(1).setCellValue("uploaded");
            row.createCell(2).setCellValue(payload);
            row.createCell(3).setCellValue("F1");
            row.createCell(4).setCellValue("");
            workbook.write(out);
            FileUploadDto dto = new FileUploadDto();
            dto.setFile(new MockMultipartFile("file", "tasks.xlsx", ProcessUtil.SHEET_NAME, out.toByteArray()));
            return dto;
        }
    }

    private static PipelineConfig entry(long tenant, String key, String kind) {
        PipelineConfig entry = new PipelineConfig();
        entry.setTenantId(tenant);
        entry.setConfigKey(key);
        entry.setKind(kind);
        return entry;
    }
}
