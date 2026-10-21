package process.security;

import org.barco.platform.security.AuditedRead;
import org.barco.platform.security.CallerIdentity;
import org.barco.platform.security.ManagedAction;
import org.barco.platform.security.ManagementMode;
import org.barco.platform.security.ManagementModeInterceptor;
import org.barco.platform.security.ReadOnlyList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ZT-M6 (owner 2026-10-21: "In a managed session, data reads are audited; lists and counts are not"). A list or a count
 * sent as a POST (its filters in the body) wrote a managed_action_log row of kind "change" for every page of it. Every
 * POST a person reaches here (not /internal, which answers a service token) is one of:
 * <ul>
 *   <li>a list or a count, marked {@link ReadOnlyList} -- exactly {@link #READ_ONLY_LISTS}, and it writes no row in a
 *       managed session;</li>
 *   <li>a data read, marked {@link AuditedRead} -- never both;</li>
 *   <li>a change, which writes its row. A change whose name reads like a list ({@link ReadOnlyList.Names}) is named in
 *       {@link #NAMED_LIKE_A_LIST_BUT_NOT_ONE}, so a new POST list without the mark fails here.</li>
 * </ul>
 */
class ReadOnlyListGuardTest {

    /** Controller.method of every POST that only lists or counts. */
    static final Set<String> READ_ONLY_LISTS = new TreeSet<>(Arrays.asList(
        "BackfillRestApi.preview",
        "FailedRunsRestApi.failedRuns",
        "MessageQRestApi.fetchLogs",
        "MessageQRestApi.runFacets",
        "RetentionRestApi.preview",
        "SourceTaskRestApi.fetchAllLinkJobsWithSourceTaskId",
        "SourceTaskRestApi.listSourceTask"));

    /** POSTs whose names read like a list but that change something (each looked at, 2026-10-21). */
    static final Set<String> NAMED_LIKE_A_LIST_BUT_NOT_ONE = new TreeSet<>();

    @AfterEach
    void clear() {
        ManagementMode.clear();
    }

    /** Every POST a person's token reaches, as Controller.method -> (path, Method, controller). */
    static Map<String, Object[]> posts() throws Exception {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Map<String, Object[]> found = new TreeMap<>();
        for (BeanDefinition candidate : scan.findCandidateComponents("process")) {
            if (candidate.getResourceDescription() != null && candidate.getResourceDescription().contains("test-classes")) {
                continue;
            }
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String prefix = base == null || base.value().length == 0 ? "" : base.value()[0];
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                boolean post = mapping.method().length == 0 || Arrays.asList(mapping.method()).contains(RequestMethod.POST);
                String path = prefix + (mapping.value().length == 0 ? "" : mapping.value()[0]);
                if (!post || path.startsWith("/internal")) {
                    continue;
                }
                found.put(controller.getSimpleName() + "." + method.getName(), new Object[] {path, method, controller});
            }
        }
        return found;
    }

    @Test
    void theMarkedPostsAreExactlyTheListsAndCounts() throws Exception {
        Set<String> marked = new TreeSet<>();
        for (Map.Entry<String, Object[]> post : posts().entrySet()) {
            Method method = (Method) post.getValue()[1];
            if (method.isAnnotationPresent(ReadOnlyList.class)) {
                marked.add(post.getKey());
                assertThat(method.isAnnotationPresent(AuditedRead.class)).as(post.getKey() + " is a list or a data read, not both")
                    .isFalse();
            }
        }
        assertThat(marked).isEqualTo(READ_ONLY_LISTS);
    }

    @Test
    void aPostNamedLikeAListIsMarkedOrNamedAsAChange() throws Exception {
        Map<String, Object[]> posts = posts();
        Set<String> unmarked = new TreeSet<>();
        for (Map.Entry<String, Object[]> post : posts.entrySet()) {
            Method method = (Method) post.getValue()[1];
            if (ReadOnlyList.Names.looksLikeAList(method.getName()) && !method.isAnnotationPresent(ReadOnlyList.class)
                && !method.isAnnotationPresent(AuditedRead.class)) {
                unmarked.add(post.getKey());
            }
        }
        assertThat(unmarked).as("a POST named like a list carries @ReadOnlyList (or @AuditedRead), or is named as a change")
            .isEqualTo(NAMED_LIKE_A_LIST_BUT_NOT_ONE);
        assertThat(posts.keySet()).containsAll(READ_ONLY_LISTS);
    }

    @Test
    void inAManagedSessionAMarkedPostWritesNoRowAndEveryOtherPostWritesItsOwn() throws Exception {
        List<ManagedAction> rows = new ArrayList<>();
        ManagementModeInterceptor audit = new ManagementModeInterceptor("process", rows::add);
        int changes = 0;
        for (Map.Entry<String, Object[]> post : posts().entrySet()) {
            Object[] v = post.getValue();
            CallerIdentity managed = new CallerIdentity(4639L, 2962L, "TENANT_ADMIN", "qa", false, "jti-" + post.getKey(), 1,
                "MANAGED", true);
            ManagementMode.set(managed);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1" + v[0]);
            request.setContextPath("/api/v1");
            int before = rows.size();
            assertThat(audit.preHandle(request, new MockHttpServletResponse(),
                new HandlerMethod(mock((Class<?>) v[2]), (Method) v[1]))).as(post.getKey()).isTrue();
            if (READ_ONLY_LISTS.contains(post.getKey())) {
                assertThat(rows).as(post.getKey() + " only lists: no row").hasSize(before);
            } else {
                assertThat(rows).as(post.getKey() + " writes its row").hasSize(before + 1);
                changes++;
            }
        }
        assertThat(changes).as("the POSTs that are not lists still write their rows").isPositive();
    }
}
