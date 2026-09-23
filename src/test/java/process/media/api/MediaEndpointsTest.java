package process.media.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import process.media.preview.ObjectPreviewServiceImpl;
import process.media.text.ObjectTextServiceImpl;
import process.model.dto.ArchiveEntryDto;
import process.model.dto.ObjectTextDto;
import process.model.dto.TablePreviewDto;

import java.util.Arrays;
import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Media's endpoints kept the URLs the console calls when they moved out of StorageBrowserRestApi
 * and AiPromptRestApi (MIG-40), and answer with the same bodies.
 */
class MediaEndpointsTest {

    private final ObjectPreviewServiceImpl preview = mock(ObjectPreviewServiceImpl.class);
    private final ObjectTextServiceImpl text = mock(ObjectTextServiceImpl.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MediaPreviewRestApi(this.preview), new ObjectTextRestApi(this.text)).build();

    @Test
    void aTablePreviewIsStillAtStorageJsonPreviewTable() throws Exception {
        TablePreviewDto table = new TablePreviewDto();
        table.setColumns(Arrays.asList("id", "name"));
        when(this.preview.table("b", "t.csv", null, 0, 10)).thenReturn(table);

        this.mvc.perform(get("/storage.json/previewTable").param("bucket", "b").param("key", "t.csv").param("offset", "0").param("limit", "10")
                .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.message").value("Table read."))
            .andExpect(jsonPath("$.data.columns[1]").value("name"));
    }

    @Test
    void aRefusalIsStillAnErrorBodyWithStatus200() throws Exception {
        when(this.preview.table("b", "big.csv", null, null, null)).thenThrow(new IllegalArgumentException("big.csv is 201 MB -- too large"));

        this.mvc.perform(get("/storage.json/previewTable").param("bucket", "b").param("key", "big.csv").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.message").value("big.csv is 201 MB -- too large"));
    }

    @Test
    void aDocumentPreviewIsStillAPdfAndAnArchiveStillAListing() throws Exception {
        when(this.preview.document("b", "d.docx")).thenReturn(new byte[] {'%', 'P', 'D', 'F'});
        when(this.preview.archive("b", "a.zip")).thenReturn(Collections.singletonList(new ArchiveEntryDto()));

        this.mvc.perform(get("/storage.json/previewDocument").param("bucket", "b").param("key", "d.docx"))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
        this.mvc.perform(get("/storage.json/previewArchive").param("bucket", "b").param("key", "a.zip").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Archive listed."));
    }

    @Test
    void objectTextIsStillAtAiPromptJsonObjectText() throws Exception {
        when(this.text.read("b", "notes.txt", null)).thenReturn(new process.model.dto.ResponseDto("SUCCESS", "Text read.", new ObjectTextDto()));

        this.mvc.perform(get("/aiPrompt.json/objectText").param("bucket", "b").param("key", "notes.txt").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
    }
}
