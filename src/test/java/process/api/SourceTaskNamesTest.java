package process.api;

import org.barco.platform.security.PlatformLevel;
import org.barco.platform.security.WorkspaceData;
import org.barco.platform.security.WorkspaceDataGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import process.config.MethodSecurityConfig;
import process.model.dto.ResponseDto;
import process.model.service.SourceTaskService;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ZT-A12 follow-up (owner 2026-10-21, "Managed session only"): the platform's rate-card editor picks a pipeline for a
 * PIPELINE pricing rule from GET sourceTask.json/names -- every workspace's pipelines as ids and names -- instead of
 * listSourceTask, which is a managed session's now. A platform administrator's own sign-in reaches it (PlatformLevel);
 * a workspace's administrator, a managed-service session included, is refused by its @PreAuthorize (403) and lists its
 * own pipelines on the Pipelines page. What it answers across workspaces: JobOwnershipPostgresTest.
 * aPlatformAdminNamesEveryWorkspacesPipelinesAndNothingMore.
 */
class SourceTaskNamesTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Configuration
    @Import(MethodSecurityConfig.class)
    static class Wiring {
        static final SourceTaskService SERVICE = mock(SourceTaskService.class);

        @Bean
        SourceTaskRestApi sourceTaskRestApi() {
            return new SourceTaskRestApi(SERVICE);
        }
    }

    private static void signIn(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("someone", "n/a",
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void itIsAGetOfThePlatformsOwnSignInBesideAWorkspaceDataController() throws Exception {
        Method names = SourceTaskRestApi.class.getMethod("names");
        RequestMapping mapping = names.getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/names");
        assertThat(mapping.method()).containsExactly(RequestMethod.GET);
        assertThat(SourceTaskRestApi.class.isAnnotationPresent(WorkspaceData.class)).isTrue();
        assertThat(names.isAnnotationPresent(PlatformLevel.class)).isTrue();
        // The guard lets the own sign-in through to it, and to nothing else of the controller's.
        assertThat(WorkspaceDataGuard.isWorkspaceData(new HandlerMethod(mock(SourceTaskRestApi.class), names))).isFalse();
        assertThat(WorkspaceDataGuard.isWorkspaceData(new HandlerMethod(mock(SourceTaskRestApi.class),
            SourceTaskRestApi.class.getMethod("downloadListSourceTask")))).isTrue();
    }

    @Test
    void aPlatformAdministratorGetsTheNamesAndAWorkspaceAdministratorIsRefused() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Wiring.class)) {
            SourceTaskRestApi api = context.getBean(SourceTaskRestApi.class);
            ResponseDto names = new ResponseDto("SUCCESS", "0 pipeline(s).", Collections.emptyList());
            when(Wiring.SERVICE.pipelineNames()).thenReturn(names);

            signIn("TENANT_ADMIN");
            // AccessDeniedException's subclass: GlobalExceptionHandler.handleAccessDenied answers it 403.
            assertThatThrownBy(api::names).isInstanceOf(AuthorizationDeniedException.class);
            signIn("TENANT_USER");
            assertThatThrownBy(api::names).isInstanceOf(AuthorizationDeniedException.class);
            verify(Wiring.SERVICE, never()).pipelineNames();

            signIn("PLATFORM_ADMIN");
            ResponseEntity<?> answer = api.names();
            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(answer.getBody()).isSameAs(names);
        }
    }
}
