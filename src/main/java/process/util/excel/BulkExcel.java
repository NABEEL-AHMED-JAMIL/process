package process.util.excel;

import java.util.List;
import com.google.gson.Gson;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Component;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper;
import org.apache.poi.xssf.usermodel.XSSFDataValidationConstraint;
import org.apache.poi.xssf.usermodel.XSSFDataValidation;
import org.apache.poi.ss.usermodel.IndexedColors;

/**
 * Spreadsheet plumbing for the bulk upload and download screens.
 *
 * The workbook being written is held per thread rather than per bean. This is one Spring
 * singleton shared by every export, and the callers set the workbook and its sheet and then
 * fill rows into them across many calls -- so two people exporting at the same moment
 * interleaved: the second setSheet landed between the first caller's header and its rows, and
 * the first caller's remaining rows were written into the second caller's sheet. One file came
 * back holding another tenant's jobs and the other came back with a header and nothing under
 * it. A request does all of its filling on the one thread, so a thread is the right scope, and
 * the callers keep the same three-step shape they already had.
 *
 * @author Nabeel Ahmed
 * */
@Component
public class BulkExcel {

    private final ThreadLocal<XSSFWorkbook> wb = new ThreadLocal<>();
    private final ThreadLocal<XSSFSheet> sheet = new ThreadLocal<>();

    public XSSFWorkbook getWb() {
        return this.wb.get();
    }

    public void setWb(XSSFWorkbook wb) {
        this.wb.set(wb);
    }

    public XSSFSheet getSheet() {
        return this.sheet.get();
    }

    public void setSheet(XSSFSheet sheet) {
        this.sheet.set(sheet);
    }

    /**
     * MIG-214: forgets this thread's workbook and sheet. Call in finally once the workbook is written: Tomcat's
     * threads are pooled, and a workbook left here stayed reachable -- every row and cell of it -- until that
     * thread built another, so each thread that ever exported held its last export.
     */
    public void clear() {
        this.wb.remove();
        this.sheet.remove();
    }

    public void fillBulkHeader(Integer rowCount, String[] HEADER_FILED_BATCH_FILE) {
        Row header = this.getSheet().createRow(rowCount);
        CellStyle style = this.cellHeadingBackgroundColorStyle(IndexedColors.GREY_25_PERCENT.getIndex());
        int start = 0;
        int index = 0;
        for (int i = start; i < HEADER_FILED_BATCH_FILE.length; i++) {
            fillHeading(index, header, style, HEADER_FILED_BATCH_FILE[i]);
            index = index + 1;
        }
    }

    public void fillBulkBody(List<String> data, Integer rowCount) {
        Row body = this.getSheet().createRow(rowCount);
        for (int i = 0; i < data.size(); i++) {
            this.fillCellValue(i, body, data.get(i));
        }
    }

    public CellStyle cellHeadingBackgroundColorStyle(short backgroundColor) {
        CellStyle style = this.getWb().createCellStyle();
        style.setFont(this.getFont());
        style.setFillForegroundColor(backgroundColor);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER_SELECTION);
        return style;
    }

    public Font getFont() {
        Font font = this.getWb().createFont();
        font.setFontName("Calibre");
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        return font;
    }

    public void fillHeading(Integer fillCellCount, Row title, CellStyle style, String value) {
        Cell cell = title.createCell(fillCellCount);
        cell.setCellStyle(style);
        this.getSheet().setColumnWidth(cell.getColumnIndex(), 30 * 255);
        cell.setCellValue(value);
    }

    public void fillCellValue(Integer fillCellCount, Row body, String value) {
        Cell cell = body.createCell(fillCellCount);
        cell.setCellValue(value);
    }

    /**
     * Per thread for the same reason the workbook is: a DataFormatter builds and caches its
     * formats as it goes and makes no thread-safety promise, so two uploads being read at once
     * were sharing that cache.
     */
    private final ThreadLocal<DataFormatter> cellFormatter = ThreadLocal.withInitial(DataFormatter::new);

    public String getCellDetail(Row row, Integer index) {
        Cell currentCell = row.getCell(index, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        return this.cellFormatter.get().formatCellValue(currentCell).trim();
    }

    /** Excel's limit on an in-cell list, separators included: a longer list must live in cells. */
    static final int EXPLICIT_LIST_LIMIT = 255;

    /** The hidden sheet a long drop-down list is kept on, one column per list. */
    static final String LIST_SHEET = "_lists";

    public void fillDropDownValue(XSSFSheet sheet, Integer firstRow, Integer lastRow, Integer col, String[] dropList) {
        XSSFDataValidationHelper dataValidationHelper = new XSSFDataValidationHelper(sheet);
        // A list longer than Excel's 255 characters (a workspace with many tasks, a platform administrator's every
        // workspace's) made POI refuse the whole template ("must be less than or equal to 255 characters"): such a list
        // goes on a hidden sheet, and the drop-down names its range.
        XSSFDataValidationConstraint dataValidationConstraint = (XSSFDataValidationConstraint) (explicitLength(dropList) <= EXPLICIT_LIST_LIMIT
            ? dataValidationHelper.createExplicitListConstraint(dropList)
            : dataValidationHelper.createFormulaListConstraint(listRange(sheet.getWorkbook(), dropList)));
        CellRangeAddressList rangeAddressList = new CellRangeAddressList(firstRow, lastRow, col, col);
        XSSFDataValidation dataValidation = (XSSFDataValidation)
            dataValidationHelper.createValidation(dataValidationConstraint, rangeAddressList);
        dataValidation.setShowErrorBox(false);
        sheet.addValidationData(dataValidation);
    }

    static int explicitLength(String[] values) {
        int length = Math.max(0, values.length - 1);
        for (String value : values) {
            length += value == null ? 0 : value.length();
        }
        return length;
    }

    /** Writes the values down a new column of the hidden list sheet and answers its absolute range. */
    static String listRange(XSSFWorkbook workbook, String[] values) {
        XSSFSheet lists = workbook.getSheet(LIST_SHEET);
        if (lists == null) {
            lists = workbook.createSheet(LIST_SHEET);
            workbook.setSheetHidden(workbook.getSheetIndex(lists), true);
        }
        Row first = lists.getRow(0);
        int column = first == null || first.getLastCellNum() < 0 ? 0 : first.getLastCellNum();
        for (int i = 0; i < values.length; i++) {
            Row row = lists.getRow(i) == null ? lists.createRow(i) : lists.getRow(i);
            row.createCell(column).setCellValue(values[i] == null ? "" : values[i]);
        }
        String letter = CellReference.convertNumToColString(column);
        return String.format("'%s'!$%s$1:$%s$%d", LIST_SHEET, letter, letter, Math.max(1, values.length));
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
