package process.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * categoryOf/isImage must be gzip-aware, the same way acceptsFileType (FileChatServiceImpl)
 * already unwraps a ".gz" wrapper before checking file type -- otherwise a gzipped image or
 * audio file would be misclassified as a plain document and get the document chat prompt's
 * bucket/path fact and export instructions instead of the content-appropriate one.
 *
 * @author Nabeel Ahmed
 */
class ContentTypeUtilTest {

    @Test
    void plainImageExtensionsAreClassifiedAsImage() {
        assertThat(ContentTypeUtil.isImage("photo.png")).isTrue();
        assertThat(ContentTypeUtil.isImage("photo.jpg")).isTrue();
        assertThat(ContentTypeUtil.isImage("photo.jpeg")).isTrue();
        assertThat(ContentTypeUtil.categoryOf("photo.png")).isEqualTo(ContentTypeUtil.ContentCategory.IMAGE);
    }

    @Test
    void plainAudioExtensionsAreClassifiedAsAudio() {
        assertThat(ContentTypeUtil.categoryOf("call.mp3")).isEqualTo(ContentTypeUtil.ContentCategory.AUDIO);
        assertThat(ContentTypeUtil.categoryOf("call.m4a")).isEqualTo(ContentTypeUtil.ContentCategory.AUDIO);
    }

    @Test
    void everythingElseIsADocumentByDefault() {
        assertThat(ContentTypeUtil.categoryOf("report.pdf")).isEqualTo(ContentTypeUtil.ContentCategory.DOCUMENT);
        assertThat(ContentTypeUtil.categoryOf("data.csv")).isEqualTo(ContentTypeUtil.ContentCategory.DOCUMENT);
        assertThat(ContentTypeUtil.isImage("report.pdf")).isFalse();
    }

    @Test
    void aGzippedImageIsStillClassifiedAsAnImageNotAPlainDocument() {
        assertThat(ContentTypeUtil.isImage("screenshot.png.gz"))
            .as("the inner extension under the .gz wrapper is what determines content type, "
                + "the same rule acceptsFileType already applies")
            .isTrue();
        assertThat(ContentTypeUtil.categoryOf("screenshot.png.gz"))
            .isEqualTo(ContentTypeUtil.ContentCategory.IMAGE);
    }

    @Test
    void aGzippedAudioFileIsStillClassifiedAsAudio() {
        assertThat(ContentTypeUtil.categoryOf("call.mp3.gz")).isEqualTo(ContentTypeUtil.ContentCategory.AUDIO);
    }

    @Test
    void aGzippedTextFileRemainsADocument() {
        assertThat(ContentTypeUtil.categoryOf("audit.json.gz")).isEqualTo(ContentTypeUtil.ContentCategory.DOCUMENT);
    }
}
