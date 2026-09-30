package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.identity.TestIdentity;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantRepository;
import process.notifications.TestNotifications;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.TaskPayloadLocationUtil;
import process.util.UserNameResolver;
import process.util.excel.BulkExcel;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * MIG-214: the task list's export and its upload template put their workbook in BulkExcel's ThreadLocal and left it
 * there, so each Tomcat thread kept the last workbook it built -- every row and cell -- until it built another. With
 * the real BulkExcel: what is under test is what it holds after the request.
 */
@ExtendWith(MockitoExtension.class)
public class SourceTaskBulkExcelReleaseTest {

    private static final long TENANT_A = 1001L;

    @Mock private QueryService queryService;
    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private SourceTaskRepository sourceTaskRepository;
    @Mock private SourceTaskTypeRepository sourceTaskTypeRepository;
    @Mock private TenantFilterHelper tenantFilterHelper;
    @Mock private TenantRepository tenantRepository;
    @Mock private TestNotifications.NoticeSink notificationCenterService;
    @Mock private UserNameResolver userNameResolver;

    private final BulkExcel bulkExcel = new BulkExcel();
    private SourceTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new SourceTaskServiceImpl(this.bulkExcel, this.queryService,
            this.sourceJobRepository, this.sourceTaskRepository, this.sourceTaskTypeRepository,
            this.tenantFilterHelper, new TaskPayloadLocationUtil(), TestIdentity.over(null, this.tenantRepository),
            TestNotifications.recording(null, this.notificationCenterService, null), this.userNameResolver);
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 1L, "admin-a@example.com");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void theTaskExportLeavesNoWorkbookOnTheThread() throws Exception {
        when(this.sourceTaskRepository.downloadListSourceTaskForTenant(TENANT_A)).thenReturn(Collections.emptyList());

        assertThat(this.service.downloadListSourceTask().size()).isPositive();

        assertThat(this.bulkExcel.getWb()).isNull();
        assertThat(this.bulkExcel.getSheet()).isNull();
    }

    @Test
    void theUploadTemplateLeavesNoWorkbookOnTheThread() throws Exception {
        assertThat(this.service.downloadSourceTaskTemplate().size()).isPositive();

        assertThat(this.bulkExcel.getWb()).isNull();
        assertThat(this.bulkExcel.getSheet()).isNull();
    }
}
