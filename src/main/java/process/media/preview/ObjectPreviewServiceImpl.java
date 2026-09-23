package process.media.preview;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.model.dto.ArchiveEntryDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.TablePreviewDto;
import process.media.extraction.ExtractionService;
import process.model.service.StorageBrowserService;
import process.util.ContentTypeUtil;
import process.media.converter.DocumentConverterFormatRegistry;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Every object in a bucket, as something the browser can show.
 *
 * The viewer used to know eight kinds and answer "no inline preview" for the rest, which in an
 * ETL bucket is most of it: the spreadsheet a job wrote, the parquet a pipeline produced, the
 * docx someone uploaded, the zip of last month's exports. This is the one place that turns a
 * key into a showable thing, by the shape of the data rather than by name:
 *
 * <ul>
 *   <li><b>Tables</b> -- CSV/TSV (delimiter sniffed, .gz unwrapped), a workbook (every sheet,
 *       POI), parquet (DuckDB), JSON lines, and a JSON array of objects -- as columns and a
 *       page of rows, with the total where it is cheap to know.</li>
 *   <li><b>Documents</b> -- anything the converter has a family for (docx, rtf, odt, pptx,
 *       html, ...) -- as a PDF for the viewer that already draws PDFs.</li>
 *   <li><b>Archives</b> -- a zip -- as its entries, so a person can see what is inside without
 *       downloading it.</li>
 * </ul>
 *
 * Bytes are read through the storage browser, so the caller's right to the bucket is the
 * storage browser's decision; nothing here widens it.
 */
@Service
public class ObjectPreviewServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(ObjectPreviewServiceImpl.class);

    public static final int MAX_LIMIT = 500;
    /** Rows counted before a CSV's total is given up as "more than this". */
    static final long COUNT_CAP = 200_000;
    /** A workbook is read whole into memory by POI; past this, download is the honest answer. */
    static final long MAX_WORKBOOK_BYTES = 25L * 1024 * 1024;
    static final long MAX_TABLE_BYTES = 200L * 1024 * 1024;
    static final int MAX_ARCHIVE_ENTRIES = 2000;

    static final Set<String> DELIMITED = new HashSet<>(Arrays.asList("csv", "tsv", "psv"));
    static final Set<String> WORKBOOK = new HashSet<>(Arrays.asList("xlsx", "xlsm", "xls"));
    static final Set<String> JSON_LINES = new HashSet<>(Arrays.asList("jsonl", "ndjson"));
    static final Set<String> ARCHIVE = new HashSet<>(Arrays.asList("zip", "jar"));

    private final StorageBrowserService storage;
    private final ExtractionService extraction;
    private final Gson gson = new Gson();

    public ObjectPreviewServiceImpl(StorageBrowserService storage, ExtractionService extraction) {
        this.storage = storage;
        this.extraction = extraction;
    }

    /** Whether {@link #table} has a reader for this key. */
    public static boolean isTabular(String key) {
        String extension = innerExtension(key);
        return DELIMITED.contains(extension) || WORKBOOK.contains(extension) || JSON_LINES.contains(extension)
            || "parquet".equals(extension) || "json".equals(extension);
    }

    /** Whether {@link #document} can turn this key into a PDF. Spreadsheets go to the table instead. */
    public static boolean isDocument(String key) {
        String extension = innerExtension(key);
        if (extension.isEmpty() || "pdf".equals(extension) || isTabular(key)) {
            return false;
        }
        DocumentConverterFormatRegistry.FormatFamily family = DocumentConverterFormatRegistry.familyOfInput(extension);
        return family != null && DocumentConverterFormatRegistry.isSupportedConversion(extension, "pdf");
    }

    public static boolean isArchive(String key) {
        return ARCHIVE.contains(ContentTypeUtil.extensionOf(key));
    }

    /** What is inside a .gz, else the extension itself. */
    static String innerExtension(String key) {
        return ContentTypeUtil.isGzip(key) ? ContentTypeUtil.innerExtensionOfGzip(key) : ContentTypeUtil.extensionOf(key);
    }

    // ---- tables ------------------------------------------------------------------------------

    public TablePreviewDto table(String bucket, String key, String sheet, Integer offsetArg, Integer limitArg) throws Exception {
        int offset = offsetArg == null || offsetArg < 0 ? 0 : offsetArg;
        int limit = limitArg == null || limitArg < 1 ? 100 : Math.min(limitArg, MAX_LIMIT);
        String extension = innerExtension(key);
        ObjectMetadataDto metadata = this.storage.getObjectMetadata(bucket, key);
        long size = metadata == null || metadata.getSize() == null ? -1 : metadata.getSize();
        if (WORKBOOK.contains(extension)) {
            if (size > MAX_WORKBOOK_BYTES) {
                throw new IllegalArgumentException(String.format("%s is %s -- too large to open as a table here; download it instead.",
                    ContentTypeUtil.fileNameOf(key), humanSize(size)));
            }
            return this.workbook(this.readAll(bucket, key), sheet, offset, limit);
        }
        if (size > MAX_TABLE_BYTES) {
            throw new IllegalArgumentException(String.format("%s is %s -- too large to preview as a table; download it instead.",
                ContentTypeUtil.fileNameOf(key), humanSize(size)));
        }
        if ("parquet".equals(extension)) {
            return this.parquet(this.readAll(bucket, key), offset, limit);
        }
        if (JSON_LINES.contains(extension)) {
            try (BufferedReader reader = this.textReader(bucket, key)) {
                return this.jsonLines(reader, offset, limit);
            }
        }
        if ("json".equals(extension)) {
            try (BufferedReader reader = this.textReader(bucket, key)) {
                return this.jsonArray(reader, offset, limit);
            }
        }
        if (DELIMITED.contains(extension)) {
            try (BufferedReader reader = this.textReader(bucket, key)) {
                return this.delimited(reader, extension, offset, limit);
            }
        }
        throw new IllegalArgumentException(String.format("There is no table reader for .%s files.", extension));
    }

    /** Header from the first record, the delimiter sniffed from it when the name does not say. */
    TablePreviewDto delimited(BufferedReader reader, String extension, int offset, int limit) throws IOException {
        reader.mark(64 * 1024);
        String first = reader.readLine();
        reader.reset();
        char delimiter = "tsv".equals(extension) ? '\t' : "psv".equals(extension) ? '|' : sniffDelimiter(first);
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setIgnoreEmptyLines(true)
            .setAllowMissingColumnNames(true).setTrim(false).build();
        TablePreviewDto out = new TablePreviewDto();
        out.setSource("tsv".equals(extension) ? "tsv" : "csv");
        out.setOffset(offset); out.setLimit(limit);
        long index = 0;
        try (CSVParser parser = new CSVParser(reader, format)) {
            for (CSVRecord record : parser) {
                if (index == 0) {
                    List<String> columns = new ArrayList<>();
                    for (String cell : record) columns.add(cell);
                    out.setColumns(columns);
                } else {
                    long dataIndex = index - 1;
                    if (dataIndex >= offset && dataIndex < (long) offset + limit) {
                        List<String> row = new ArrayList<>(out.getColumns().size());
                        for (String cell : record) row.add(cell);
                        out.getRows().add(row);
                    }
                    if (dataIndex >= COUNT_CAP && out.getRows().size() >= limit) {
                        out.setTotalRows(-1);
                        out.setNote(String.format("More than %,d rows; the count stopped there.", COUNT_CAP));
                        return out;
                    }
                }
                index++;
            }
        }
        out.setTotalRows(Math.max(0, index - 1));
        return out;
    }

    /** The most frequent of the usual separators on the header line; a comma when nothing is. */
    static char sniffDelimiter(String line) {
        if (line == null) {
            return ',';
        }
        char best = ',';
        int bestCount = -1;
        for (char candidate : new char[] {',', '\t', ';', '|'}) {
            int count = 0;
            boolean quoted = false;
            for (char c : line.toCharArray()) {
                if (c == '"') quoted = !quoted;
                else if (c == candidate && !quoted) count++;
            }
            if (count > bestCount) { best = candidate; bestCount = count; }
        }
        return best;
    }

    TablePreviewDto workbook(byte[] bytes, String sheetName, int offset, int limit) throws Exception {
        TablePreviewDto out = new TablePreviewDto();
        out.setSource("xlsx"); out.setOffset(offset); out.setLimit(limit);
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) out.getSheets().add(workbook.getSheetName(i));
            Sheet sheet = sheetName == null || sheetName.isEmpty() ? workbook.getSheetAt(0) : workbook.getSheet(sheetName);
            if (sheet == null) {
                throw new IllegalArgumentException(String.format("There is no sheet called \"%s\".", sheetName));
            }
            out.setSheet(sheet.getSheetName());
            int firstRow = sheet.getFirstRowNum();
            int lastRow = sheet.getLastRowNum();
            Row header = sheet.getRow(firstRow);
            int width = header == null ? 0 : header.getLastCellNum();
            List<String> columns = new ArrayList<>();
            for (int c = 0; c < width; c++) {
                Cell cell = header.getCell(c);
                String text = cell == null ? "" : formatter.formatCellValue(cell);
                columns.add(text.isEmpty() ? columnLetter(c) : text);
            }
            out.setColumns(columns);
            long total = header == null ? 0 : Math.max(0, lastRow - firstRow);
            out.setTotalRows(total);
            for (long dataIndex = offset; dataIndex < Math.min(total, (long) offset + limit); dataIndex++) {
                Row row = sheet.getRow((int) (firstRow + 1 + dataIndex));
                List<String> values = new ArrayList<>(width);
                for (int c = 0; c < width; c++) {
                    Cell cell = row == null ? null : row.getCell(c);
                    values.add(cell == null ? "" : formatter.formatCellValue(cell));
                }
                out.getRows().add(values);
            }
        }
        return out;
    }

    static String columnLetter(int index) {
        StringBuilder s = new StringBuilder();
        int n = index;
        do { s.insert(0, (char) ('A' + n % 26)); n = n / 26 - 1; } while (n >= 0);
        return s.toString();
    }

    /** DuckDB reads the file from disk; a temp copy is the price of not shipping a parquet reader. */
    TablePreviewDto parquet(byte[] bytes, int offset, int limit) throws Exception {
        Path temp = Files.createTempFile("preview-", ".parquet");
        try {
            Files.write(temp, bytes);
            String path = temp.toAbsolutePath().toString().replace("'", "''");
            TablePreviewDto out = new TablePreviewDto();
            out.setSource("parquet"); out.setOffset(offset); out.setLimit(limit);
            try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
                 Statement count = duck.createStatement();
                 ResultSet total = count.executeQuery("select count(*) from read_parquet('" + path + "')")) {
                total.next();
                out.setTotalRows(total.getLong(1));
                try (PreparedStatement page = duck.prepareStatement(
                        "select * from read_parquet('" + path + "') limit ? offset ?")) {
                    page.setInt(1, limit); page.setInt(2, offset);
                    try (ResultSet rows = page.executeQuery()) {
                        ResultSetMetaData meta = rows.getMetaData();
                        for (int c = 1; c <= meta.getColumnCount(); c++) out.getColumns().add(meta.getColumnLabel(c));
                        while (rows.next()) {
                            List<String> row = new ArrayList<>(meta.getColumnCount());
                            for (int c = 1; c <= meta.getColumnCount(); c++) {
                                Object value = rows.getObject(c);
                                row.add(value == null ? "" : String.valueOf(value));
                            }
                            out.getRows().add(row);
                        }
                    }
                }
            }
            return out;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** One JSON object per line; the columns are every key seen, in the order they first appear. */
    TablePreviewDto jsonLines(BufferedReader reader, int offset, int limit) throws IOException {
        TablePreviewDto out = new TablePreviewDto();
        out.setSource("jsonl"); out.setOffset(offset); out.setLimit(limit);
        List<JsonObject> page = new ArrayList<>();
        Map<String, Integer> columns = new LinkedHashMap<>();
        long index = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            JsonElement element;
            try {
                element = JsonParser.parseString(line);
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException(String.format("Line %d is not JSON.", index + 1));
            }
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(String.format("Line %d is not a JSON object.", index + 1));
            }
            JsonObject object = element.getAsJsonObject();
            for (String name : object.keySet()) columns.putIfAbsent(name, columns.size());
            if (index >= offset && index < (long) offset + limit) page.add(object);
            index++;
            if (index >= COUNT_CAP + offset + limit) {
                out.setTotalRows(-1);
                out.setNote(String.format("More than %,d rows; the count stopped there.", COUNT_CAP));
                break;
            }
        }
        if (out.getTotalRows() != -1) out.setTotalRows(index);
        this.fill(out, columns, page);
        return out;
    }

    /** A JSON array of objects is a table; anything else is not, and says so. */
    TablePreviewDto jsonArray(BufferedReader reader, int offset, int limit) throws IOException {
        JsonElement root;
        try {
            root = JsonParser.parseReader(reader);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Not valid JSON.");
        }
        if (root.isJsonObject() && root.getAsJsonObject().size() == 1) {
            // {"data": [...]}: an envelope around one list is that list. An object with more
            // in it than the list -- a report with a summary and a "reasons" array -- is a
            // document, and reads whole as text rather than as one of its parts.
            JsonElement only = root.getAsJsonObject().entrySet().iterator().next().getValue();
            if (only.isJsonArray() && isArrayOfObjects(only.getAsJsonArray())) {
                root = only;
            }
        }
        if (!root.isJsonArray() || !isArrayOfObjects(root.getAsJsonArray())) {
            throw new IllegalArgumentException("This JSON is not a list of records, so it reads better as text.");
        }
        JsonArray array = root.getAsJsonArray();
        TablePreviewDto out = new TablePreviewDto();
        out.setSource("json"); out.setOffset(offset); out.setLimit(limit);
        out.setTotalRows(array.size());
        Map<String, Integer> columns = new LinkedHashMap<>();
        List<JsonObject> page = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonObject object = array.get(i).getAsJsonObject();
            for (String name : object.keySet()) columns.putIfAbsent(name, columns.size());
            if (i >= offset && i < offset + limit) page.add(object);
        }
        this.fill(out, columns, page);
        return out;
    }

    private static boolean isArrayOfObjects(JsonArray array) {
        if (array.size() == 0) return false;
        for (JsonElement element : array) if (!element.isJsonObject()) return false;
        return true;
    }

    private void fill(TablePreviewDto out, Map<String, Integer> columns, List<JsonObject> page) {
        out.setColumns(new ArrayList<>(columns.keySet()));
        for (JsonObject object : page) {
            List<String> row = new ArrayList<>(columns.size());
            for (String name : columns.keySet()) {
                JsonElement value = object.get(name);
                row.add(value == null || value.isJsonNull() ? ""
                    : value.isJsonPrimitive() ? value.getAsString() : this.gson.toJson(value));
            }
            out.getRows().add(row);
        }
    }

    // ---- documents ---------------------------------------------------------------------------

    /** The object as a PDF, for the viewer. Null when the converter could not. */
    public byte[] document(String bucket, String key) throws Exception {
        if (!isDocument(key)) {
            throw new IllegalArgumentException(String.format("There is no document reader for .%s files.", innerExtension(key)));
        }
        byte[] pdf = this.extraction.convertContent(this.readAll(bucket, key), innerExtension(key), "pdf");
        if (pdf == null || pdf.length == 0) {
            throw new IllegalStateException(String.format("%s could not be rendered; download it to open it in its own application.",
                ContentTypeUtil.fileNameOf(key)));
        }
        return pdf;
    }

    // ---- archives ----------------------------------------------------------------------------

    public List<ArchiveEntryDto> archive(String bucket, String key) throws Exception {
        if (!isArchive(key)) {
            throw new IllegalArgumentException(String.format("There is no archive reader for .%s files.", ContentTypeUtil.extensionOf(key)));
        }
        List<ArchiveEntryDto> entries = new ArrayList<>();
        ObjectContentDto content = this.storage.downloadObject(bucket, key, null, null);
        try (ZipInputStream zip = new ZipInputStream(content.getContent())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ArchiveEntryDto dto = new ArchiveEntryDto();
                dto.setName(entry.getName());
                dto.setDirectory(entry.isDirectory());
                long size = entry.getSize();
                if (size < 0) {
                    // Written as a stream, the size sits after the data; the stream is being
                    // read anyway, so count it on the way past.
                    byte[] chunk = new byte[16 * 1024];
                    int read;
                    size = 0;
                    while ((read = zip.read(chunk)) != -1) size += read;
                }
                dto.setSize(size);
                dto.setCompressedSize(entry.getCompressedSize());
                dto.setLastModified(entry.getTime());
                entries.add(dto);
                zip.closeEntry();
                if (entries.size() >= MAX_ARCHIVE_ENTRIES) {
                    break;
                }
            }
        }
        return entries;
    }

    // ---- reading -----------------------------------------------------------------------------

    private byte[] readAll(String bucket, String key) throws Exception {
        ObjectContentDto content = this.storage.downloadObject(bucket, key, null, null);
        try (InputStream in = ContentTypeUtil.isGzip(key) ? new GZIPInputStream(content.getContent()) : content.getContent()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            int read;
            while ((read = in.read(chunk)) != -1) out.write(chunk, 0, read);
            return out.toByteArray();
        }
    }

    private BufferedReader textReader(String bucket, String key) throws Exception {
        ObjectContentDto content = this.storage.downloadObject(bucket, key, null, null);
        InputStream in = ContentTypeUtil.isGzip(key) ? new GZIPInputStream(content.getContent()) : content.getContent();
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        BufferedReader buffered = new BufferedReader(reader, 64 * 1024);
        // A UTF-8 BOM would otherwise become part of the first column's name.
        buffered.mark(1);
        int first = buffered.read();
        if (first != 0xFEFF) buffered.reset();
        return buffered;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
