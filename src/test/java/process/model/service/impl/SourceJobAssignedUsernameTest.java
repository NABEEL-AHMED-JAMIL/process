package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import process.identity.TestIdentity;
import process.model.pojo.AppUser;
import process.model.enums.UserRole;
import org.springframework.test.util.ReflectionTestUtils;
import process.util.UserNameResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.engine.ProducerBulkEngine;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobDto;
import process.model.enums.Status;
import process.model.pojo.SourceJob;
import process.model.repository.*;
import process.model.dto.UserActivityDto;
import java.sql.Timestamp;
import java.util.Collections;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.OpenSearchAuditLogClient;

import javax.persistence.EntityManager;
import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.Arrays;
import process.notifications.TestNotifications;

/**
 * MIG-107: source_job.assigned_username is written by process, from IdentityPort, wherever the assignee
 * is -- a trigger reading app_user kept it before (V85), and app_user is Identity's. V69.1 drops the
 * trigger at the cutover; until then it writes the same value.
 */
@ExtendWith(MockitoExtension.class)
public class SourceJobAssignedUsernameTest {

    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private SchedulerRepository schedulerRepository;
    @Mock private SourceTaskRepository sourceTaskRepository;
    @Mock private JobAuditLogRepository jobAuditLogRepository;
    @Mock private JobQueueRepository jobQueueRepository;
    @Mock private TaskReferenceRepository taskReferenceRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private ProducerBulkEngine producerBulkEngine;
    @Mock private TenantFilterHelper tenantFilterHelper;
    @Mock private OpenSearchAuditLogClient openSearchAuditLogClient;
    @Mock private UserNameResolver userNameResolver;

    private SourceJobServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new SourceJobServiceImpl(this.sourceJobRepository, this.schedulerRepository,
            this.sourceTaskRepository, this.jobAuditLogRepository,
            this.jobQueueRepository, this.taskReferenceRepository, TestIdentity.over(this.appUserRepository, null),
            this.producerBulkEngine, this.tenantFilterHelper, this.openSearchAuditLogClient,
            TestNotifications.recording(null, null, null, null), this.userNameResolver);
    }

    @Test
    void anAssignmentCarriesTheAssigneesUsername() {
        AppUser user = new AppUser();
        user.setAppUserId(70L);
        user.setTenantId(1001L);
        user.setUsername("olivia@a.example");
        user.setUserRole(UserRole.TENANT_USER);
        user.setStatus(Status.Active);
        when(this.appUserRepository.findById(70L)).thenReturn(Optional.of(user));
        SourceJob job = new SourceJob();

        ReflectionTestUtils.invokeMethod(this.service, "assign", job, 70L);

        assertThat(job.getAssignedUserId()).isEqualTo(70L);
        assertThat(job.getAssignedUsername()).isEqualTo("olivia@a.example");
    }

    /** Someone Identity does not know: the id is kept, the name is empty -- as the trigger's lookup left it. */
    @Test
    void anAssigneeIdentityDoesNotKnowHasNoUsername() {
        when(this.appUserRepository.findById(71L)).thenReturn(Optional.empty());
        SourceJob job = new SourceJob();
        job.setAssignedUsername("previous@a.example");

        ReflectionTestUtils.invokeMethod(this.service, "assign", job, 71L);

        assertThat(job.getAssignedUserId()).isEqualTo(71L);
        assertThat(job.getAssignedUsername()).isNull();
    }

    @Test
    void noAssigneeIsNoUsername() {
        SourceJob job = new SourceJob();
        job.setAssignedUsername("previous@a.example");

        ReflectionTestUtils.invokeMethod(this.service, "assign", job, (Long) null);

        assertThat(job.getAssignedUserId()).isNull();
        assertThat(job.getAssignedUsername()).isNull();
    }
}
