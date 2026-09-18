package process.model.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ObjectTextDto;
import process.model.dto.ResponseDto;
import process.model.service.FileChatExtractionService;
import process.model.service.StorageBrowserService;
import process.util.ProcessUtil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A file in a bucket, as a variable's text: read by the file chat's extractor, capped for a
 * prompt, and refused in words when there is nothing to read.
 */
@ExtendWith(MockitoExtension.class)
class ObjectTextServiceImplTest {

    @Mock private StorageBrowserService storage;
    @Mock private FileChatExtractionService extraction;

    private ObjectMetadataDto meta(String key, long size) {
        return new ObjectMetadataDto(key.substring(key.lastIndexOf('/') + 1), key, size, "2026-09-18T00:00:00Z", "etag-1", "text/plain", true);
    }

    @Test
    void readsAnObjectThroughTheChatsExtractorAndSaysHowTheWordsCame() throws Exception {
        when(this.storage.getObjectMetadata("b", "claims/in/one.txt")).thenReturn(meta("claims/in/one.txt", 12));
        when(this.extraction.extractText("b", "claims/in/one.txt", "etag-1")).thenReturn("hello there");

        ResponseDto out = new ObjectTextServiceImpl(this.storage, this.extraction).read("b", "claims/in/one.txt", null);

        assertThat(out.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ObjectTextDto text = (ObjectTextDto) out.getData();
        assertThat(text.getText()).isEqualTo("hello there");
        assertThat(text.getKind()).isEqualTo("text");
        assertThat(text.getName()).isEqualTo("one.txt");
        assertThat(text.isTruncated()).isFalse();
    }

    @Test
    void audioIsATranscriptAndAnImageIsADescription() {
        assertThat(ObjectTextServiceImpl.kindOf("audio/in/visit.m4a")).isEqualTo("transcript");
        assertThat(ObjectTextServiceImpl.kindOf("images/xray/chest.png")).isEqualTo("description");
        assertThat(ObjectTextServiceImpl.kindOf("docs/report.pdf")).isEqualTo("text");
        assertThat(ObjectTextServiceImpl.kindOf("logs/app.log.gz")).isEqualTo("text");
    }

    @Test
    void capsAtTheVariablesWorthAndSaysSo() throws Exception {
        String big = new String(new char[ObjectTextServiceImpl.MAX_CHARS + 5]).replace('\0', 'x');
        when(this.storage.getObjectMetadata("b", "big.csv")).thenReturn(meta("big.csv", big.length()));
        when(this.extraction.extractText("b", "big.csv", "etag-1")).thenReturn(big);

        ResponseDto out = new ObjectTextServiceImpl(this.storage, this.extraction).read("b", "big.csv", null);

        ObjectTextDto text = (ObjectTextDto) out.getData();
        assertThat(text.isTruncated()).isTrue();
        assertThat(text.getChars()).isEqualTo(ObjectTextServiceImpl.MAX_CHARS);
        assertThat(text.getTotalChars()).isEqualTo(big.length());
        assertThat(out.getMessage()).contains("first 60,000 of 60,005");
        // A smaller ask is honoured; a larger one is not.
        ObjectTextDto small = (ObjectTextDto) new ObjectTextServiceImpl(this.storage, this.extraction).read("b", "big.csv", 10).getData();
        assertThat(small.getChars()).isEqualTo(10);
    }

    @Test
    void nothingToReadIsSaidInWordsNotAsAFailure() throws Exception {
        ObjectTextServiceImpl service = new ObjectTextServiceImpl(this.storage, this.extraction);

        when(this.storage.getObjectMetadata("b", "x.parquet")).thenReturn(meta("x.parquet", 5));
        when(this.extraction.extractText("b", "x.parquet", "etag-1")).thenReturn(null);
        assertThat(service.read("b", "x.parquet", null).getMessage()).isEqualTo("There is no reader for .parquet files yet.");

        when(this.storage.getObjectMetadata("b", "empty.txt")).thenReturn(meta("empty.txt", 0));
        when(this.extraction.extractText("b", "empty.txt", "etag-1")).thenReturn("");
        assertThat(service.read("b", "empty.txt", null).getMessage()).contains("empty.txt is empty");

        when(this.storage.getObjectMetadata("b", "locked.pdf")).thenReturn(meta("locked.pdf", 9));
        when(this.extraction.extractText("b", "locked.pdf", "etag-1"))
            .thenThrow(new FileChatExtractionService.UnreadableFileException("This PDF is password-protected."));
        assertThat(service.read("b", "locked.pdf", null).getMessage()).isEqualTo("This PDF is password-protected.");

        assertThat(service.read("b", "folder/", null).getMessage()).isEqualTo("Name a bucket and a file in it.");
        assertThat(service.read("", "k", null).getStatus()).isEqualTo(ProcessUtil.ERROR);
    }
}
