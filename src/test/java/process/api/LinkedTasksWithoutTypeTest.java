package process.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import process.config.GlobalExceptionHandler;
import process.model.repository.SourceTaskRepository;
import process.model.service.impl.SourceTaskServiceImpl;
import process.security.TenantContext;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MIG-259: GET sourceTask.json/fetchAllLinkSourceTaskWithSourceTaskTypeId without a type answered 500 for every
 * caller -- the optional parameter reached the query as a null bound as bytea ("operator does not exist:
 * bigint = bytea"). The api-check suite's matrix found it. No type names no linked task: a refusal, 200 + ERROR,
 * and nothing is read.
 */
class LinkedTasksWithoutTypeTest {

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    @Test
    void noTypeIsARefusalNotA500() throws Exception {
        SourceTaskRepository tasks = mock(SourceTaskRepository.class);
        SourceTaskServiceImpl service = new SourceTaskServiceImpl(null, null, null, tasks, null, null, null, null, null, null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SourceTaskRestApi(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String role : new String[] { "TENANT_ADMIN", "PLATFORM_ADMIN" }) {
            TenantContext.set("PLATFORM_ADMIN".equals(role) ? null : 2905L, role, 4385L, "emily@medaxis");
            mvc.perform(get("/sourceTask.json/fetchAllLinkSourceTaskWithSourceTaskTypeId"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.message").value("Source task type missing."));
        }
        verifyNoInteractions(tasks);
    }
}
