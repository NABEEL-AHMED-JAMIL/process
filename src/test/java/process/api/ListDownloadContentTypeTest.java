package process.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import process.model.service.SourceJobBulkService;
import process.model.service.SourceTaskService;
import process.util.ProcessUtil;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MIG-305: the job and task list downloads and their templates are workbooks, and their Content-Type says so.
 * They went out as application/json -- the MIG-222 baseline recorded it -- whatever the caller accepted.
 */
class ListDownloadContentTypeTest {

    private static final MediaType XLSX = MediaType.parseMediaType(ProcessUtil.SHEET_NAME);
    private static final byte[] WORKBOOK = "PK\u0003\u0004 workbook".getBytes(StandardCharsets.ISO_8859_1);

    private final SourceJobBulkService jobs = mock(SourceJobBulkService.class);
    private final SourceTaskService tasks = mock(SourceTaskService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SourceJobRestApi(null, this.jobs, null),
        new SourceTaskRestApi(this.tasks)).build();

    private static ByteArrayOutputStream workbook() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(WORKBOOK);
        return out;
    }

    @Test
    void everyListAndTemplateDownloadIsLabelledAsAWorkbook() throws Exception {
        when(this.jobs.downloadListSourceJob()).thenReturn(workbook());
        when(this.jobs.downloadSourceJobTemplateFile()).thenReturn(workbook());
        when(this.tasks.downloadListSourceTask()).thenReturn(workbook());
        when(this.tasks.downloadSourceTaskTemplate()).thenReturn(workbook());

        for (String path : new String[] {"/sourceJob.json/downloadListSourceJob", "/sourceJob.json/downloadSourceJobTemplateFile",
            "/sourceTask.json/downloadListSourceTask", "/sourceTask.json/downloadSourceTaskTemplate"}) {
            // As the console asks (anything), and as a client that prefers JSON: the workbook is labelled a workbook either way.
            for (String accept : new String[] {"*/*", "application/json, text/plain, */*"}) {
                this.mvc.perform(get(path).header("Accept", accept))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(XLSX))
                    .andExpect(header().string(ProcessUtil.CONTENT_DISPOSITION, endsWith(".xlsx")))
                    .andExpect(content().bytes(WORKBOOK));
            }
        }
    }
}
