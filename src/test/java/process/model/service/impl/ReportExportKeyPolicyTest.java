package process.model.service.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import process.model.dto.ReportExportRequestDto;
import process.model.service.StorageBrowserService;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Report export's key policy, one of three that must not be conflated (MIG-158). Storage's guarded
 * path REFUSES an unsafe key; the analytics export ALLOW-LISTS its folder and refuses the rest; report
 * export STRIPS: it assembles the key itself from a folder the user typed, removing leading slashes,
 * every "..", and trailing slashes, and never refuses. Harmonising them breaks two of the three:
 * refusing here would turn a typo'd folder into a failed export, and stripping in Storage would act
 * on a key the caller never asked for.
 *
 * The strip is not the last line: the key still goes through StorageBrowserService's guarded upload,
 * whose isSafeKey refuses whatever the strip leaves behind that is still not a key -- "../../etc"
 * becomes "//etc/...", which starts with a slash and is refused there.
 */
class ReportExportKeyPolicyTest {

    private final StorageBrowserService storage = mock(StorageBrowserService.class);
    private final ReportExportServiceImpl export = new ReportExportServiceImpl(null, this.storage, null);

    private String keyWrittenFor(String folder) {
        ReportExportRequestDto dto = new ReportExportRequestDto();
        dto.setTitle("Q3");
        dto.setColumns(Arrays.asList("region", "orders"));
        dto.setRows(Collections.singletonList(Arrays.asList((Object) "south", 2)));
        dto.setFormat("csv");
        dto.setDestination("bucket");
        dto.setBucket("tenant-a-exports");
        dto.setFolder(folder);
        this.export.export(dto);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(this.storage, atLeastOnce()).uploadObject(eq("tenant-a-exports"), key.capture(), any(InputStream.class), anyLong(), any());
        return key.getValue();
    }

    @Test
    void aClimbIsStrippedFromTheFolderRatherThanRefused() {
        assertThat(keyWrittenFor("q3/../archive")).startsWith("q3//archive/");
        assertThat(keyWrittenFor("q3/..archive")).startsWith("q3/archive/");
    }

    @Test
    void leadingAndTrailingSlashesAreStripped() {
        assertThat(keyWrittenFor("/reports/q3/")).startsWith("reports/q3/").doesNotStartWith("/");
    }

    /** What the strip cannot make safe, it hands on -- and Storage's guarded upload refuses it. */
    @Test
    void whatTheStripLeavesUnsafeIsLeftForStoragesRefusal() {
        assertThat(keyWrittenFor("../../etc")).startsWith("//etc/");
    }

    @Test
    void noFolderMeansReports() {
        assertThat(keyWrittenFor(null)).startsWith("reports/");
    }
}
