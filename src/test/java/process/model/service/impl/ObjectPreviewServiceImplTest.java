package process.model.service.impl;

import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.ArchiveEntryDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.TablePreviewDto;
import process.model.service.FileChatExtractionService;
import process.model.service.StorageBrowserService;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Every shape of data in a bucket, as a table, a document or a listing -- and the honest refusal
 * for what is none of those.
 */
@ExtendWith(MockitoExtension.class)
class ObjectPreviewServiceImplTest {

    @Mock private StorageBrowserService storage;
    @Mock private FileChatExtractionService extraction;

    private ObjectPreviewServiceImpl service() { return new ObjectPreviewServiceImpl(this.storage, this.extraction); }

    private void object(String key, byte[] bytes) {
        // Lenient: the archive path lists straight from the stream and never asks for metadata.
        lenient().when(this.storage.getObjectMetadata("b", key)).thenReturn(
            new ObjectMetadataDto(key, key, (long) bytes.length, "2026-09-18T00:00:00Z", "e", "application/octet-stream", true));
        when(this.storage.downloadObject(eq("b"), eq(key), any(), any()))
            .thenAnswer(inv -> new ObjectContentDto(new ByteArrayInputStream(bytes), "application/octet-stream", bytes.length, key));
    }

    private static BufferedReader reader(String text) { return new BufferedReader(new StringReader(text)); }

    @Test
    void aCsvIsAHeaderAndAPageOfRowsWithTheDelimiterSniffed() throws Exception {
        TablePreviewDto semi = service().delimited(reader("a;b;c\n1;2;3\n4;5;6\n7;8;9\n"), "csv", 1, 1);
        assertThat(semi.getColumns()).containsExactly("a", "b", "c");
        assertThat(semi.getRows()).containsExactly(Arrays.asList("4", "5", "6"));
        assertThat(semi.getTotalRows()).isEqualTo(3);
        assertThat(semi.getOffset()).isEqualTo(1);

        TablePreviewDto quoted = service().delimited(reader("name,note\n\"Smith, J\",\"says \"\"hi\"\"\"\n"), "csv", 0, 10);
        assertThat(quoted.getRows()).containsExactly(Arrays.asList("Smith, J", "says \"hi\""));

        assertThat(ObjectPreviewServiceImpl.sniffDelimiter("x\ty\tz")).isEqualTo('\t');
        assertThat(ObjectPreviewServiceImpl.sniffDelimiter("x|y|z")).isEqualTo('|');
        assertThat(ObjectPreviewServiceImpl.sniffDelimiter("\"a,b\";c")).isEqualTo(';');
        assertThat(ObjectPreviewServiceImpl.sniffDelimiter("single")).isEqualTo(',');
    }

    @Test
    void aGzippedCsvReadsAsTheCsvInsideIt() throws Exception {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) { out.write("﻿id,v\n1,x\n2,y\n".getBytes(StandardCharsets.UTF_8)); }
        object("sales/rows.csv.gz", gz.toByteArray());

        TablePreviewDto table = service().table("b", "sales/rows.csv.gz", null, 0, 50);

        assertThat(table.getSource()).isEqualTo("csv");
        // The BOM is not part of the first column's name.
        assertThat(table.getColumns()).containsExactly("id", "v");
        assertThat(table.getRows()).hasSize(2);
        assertThat(ObjectPreviewServiceImpl.isTabular("sales/rows.csv.gz")).isTrue();
    }

    @Test
    void aWorkbookListsItsSheetsAndReadsTheOneAsked() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet orders = workbook.createSheet("Orders");
            XSSFRow header = orders.createRow(0); header.createCell(0).setCellValue("id"); header.createCell(1).setCellValue("amount");
            for (int i = 1; i <= 3; i++) { XSSFRow row = orders.createRow(i); row.createCell(0).setCellValue(4000 + i); row.createCell(1).setCellValue(19.5 * i); }
            XSSFSheet totals = workbook.createSheet("Totals");
            totals.createRow(0).createCell(0).setCellValue("region"); totals.createRow(1).createCell(0).setCellValue("north");
            workbook.write(bytes);
        }
        object("docs/orders.xlsx", bytes.toByteArray());

        TablePreviewDto first = service().table("b", "docs/orders.xlsx", null, 0, 100);
        assertThat(first.getSheets()).containsExactly("Orders", "Totals");
        assertThat(first.getSheet()).isEqualTo("Orders");
        assertThat(first.getColumns()).containsExactly("id", "amount");
        assertThat(first.getTotalRows()).isEqualTo(3);
        assertThat(first.getRows().get(0)).containsExactly("4001", "19.5");

        TablePreviewDto second = service().table("b", "docs/orders.xlsx", "Totals", 0, 100);
        assertThat(second.getColumns()).containsExactly("region");
        assertThat(second.getRows()).containsExactly(Arrays.asList("north"));

        assertThatThrownBy(() -> service().table("b", "docs/orders.xlsx", "Nope", 0, 100))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no sheet called");
        assertThat(ObjectPreviewServiceImpl.columnLetter(0)).isEqualTo("A");
        assertThat(ObjectPreviewServiceImpl.columnLetter(27)).isEqualTo("AB");
    }

    @Test
    void jsonLinesAndAJsonArrayAreTablesWithEveryKeyAsAColumn() throws Exception {
        TablePreviewDto lines = service().jsonLines(reader("{\"a\":1,\"b\":\"x\"}\n\n{\"a\":2,\"c\":{\"n\":true}}\n"), 0, 10);
        assertThat(lines.getColumns()).containsExactly("a", "b", "c");
        assertThat(lines.getRows()).containsExactly(Arrays.asList("1", "x", ""), Arrays.asList("2", "", "{\"n\":true}"));
        assertThat(lines.getTotalRows()).isEqualTo(2);

        TablePreviewDto array = service().jsonArray(reader("{\"data\":[{\"id\":1},{\"id\":2},{\"id\":3}]}"), 1, 5);
        assertThat(array.getColumns()).containsExactly("id");
        assertThat(array.getRows()).containsExactly(Arrays.asList("2"), Arrays.asList("3"));
        assertThat(array.getTotalRows()).isEqualTo(3);

        assertThatThrownBy(() -> service().jsonArray(reader("{\"only\":\"an object\"}"), 0, 5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reads better as text");
        // A report with a summary and a list inside is a document, not the list.
        assertThatThrownBy(() -> service().jsonArray(reader("{\"batch\":\"B1\",\"rejected\":2,\"reasons\":[{\"row\":1}]}"), 0, 5))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().jsonLines(reader("not json\n"), 0, 5))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Line 1");
    }

    @Test
    void aParquetFileIsReadByDuckDb() throws Exception {
        Path temp = Files.createTempFile("test-", ".parquet");
        Files.delete(temp);
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:"); Statement s = duck.createStatement()) {
            s.execute("copy (select range as id, 'row-' || range as label from range(7)) to '" + temp + "' (format parquet)");
        }
        byte[] bytes = Files.readAllBytes(temp);
        Files.delete(temp);
        object("edge/data.parquet", bytes);

        TablePreviewDto table = service().table("b", "edge/data.parquet", null, 5, 10);

        assertThat(table.getSource()).isEqualTo("parquet");
        assertThat(table.getColumns()).containsExactly("id", "label");
        assertThat(table.getTotalRows()).isEqualTo(7);
        assertThat(table.getRows()).containsExactly(Arrays.asList("5", "row-5"), Arrays.asList("6", "row-6"));
    }

    @Test
    void aZipIsListedWithoutBeingExtracted() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("exports/")); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("exports/jobs.csv")); zip.write("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        object("archive/exports.zip", bytes.toByteArray());

        List<ArchiveEntryDto> entries = service().archive("b", "archive/exports.zip");

        assertThat(entries).extracting(ArchiveEntryDto::getName).containsExactly("exports/", "exports/jobs.csv");
        assertThat(entries.get(0).isDirectory()).isTrue();
        assertThat(entries.get(1).getSize()).isEqualTo(8);
        assertThat(ObjectPreviewServiceImpl.isArchive("a.zip")).isTrue();
        assertThat(ObjectPreviewServiceImpl.isArchive("a.tar.gz")).isFalse();
    }

    @Test
    void whatIsADocumentAndWhatIsNot() {
        assertThat(ObjectPreviewServiceImpl.isDocument("notes.docx")).isTrue();
        assertThat(ObjectPreviewServiceImpl.isDocument("deck.pptx")).isTrue();
        assertThat(ObjectPreviewServiceImpl.isDocument("page.html")).isTrue();
        // Already a PDF, a table, or nothing the converter knows: not a document to render.
        assertThat(ObjectPreviewServiceImpl.isDocument("scan.pdf")).isFalse();
        assertThat(ObjectPreviewServiceImpl.isDocument("orders.xlsx")).isFalse();
        assertThat(ObjectPreviewServiceImpl.isDocument("data.parquet")).isFalse();
        assertThat(ObjectPreviewServiceImpl.isDocument("NOTES")).isFalse();
    }

    @Test
    void tooLargeIsSaidWithTheSizeNotAttempted() {
        when(this.storage.getObjectMetadata("b", "huge.xlsx")).thenReturn(
            new ObjectMetadataDto("huge.xlsx", "huge.xlsx", ObjectPreviewServiceImpl.MAX_WORKBOOK_BYTES + 1, "", "e", "x", true));
        assertThatThrownBy(() -> service().table("b", "huge.xlsx", null, 0, 10))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("25.0 MB").hasMessageContaining("download");
    }
}
