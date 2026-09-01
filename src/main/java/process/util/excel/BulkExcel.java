package process.util.excel;

import java.util.List;
import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Component;

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

    private Logger logger = LogManager.getLogger(BulkExcel.class);

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

    public void fillBulkHeader(Integer rowCount, String[] HEADER_FILED_BATCH_FILE) {
        Row header = this.getSheet().createRow(rowCount);
        CellStyle style = this.cellHeadingBackgroundColorStyle(IndexedColors.GREY_25_PERCENT.getIndex());
        int start = 0; int index = 0;
        for (int i=start; i<HEADER_FILED_BATCH_FILE.length; i++) {
            fillHeading(index, header, style, HEADER_FILED_BATCH_FILE[i]);
            index = index+1;
        }
    }

    public void fillBulkBody(List<String> data, Integer rowCount) {
        Row body = this.getSheet().createRow(rowCount);
        for(int i=0; i<data.size(); i++) {
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
        this.getSheet().setColumnWidth(cell.getColumnIndex(), 30*255);
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

    public void fillDropDownValue(XSSFSheet sheet, Integer firstRow, Integer lastRow, Integer col, String[] dropList) {
        XSSFDataValidationHelper dataValidationHelper = new XSSFDataValidationHelper(sheet);
        XSSFDataValidationConstraint dataValidationConstraint = (XSSFDataValidationConstraint)
            dataValidationHelper.createExplicitListConstraint(dropList);
        CellRangeAddressList rangeAddressList = new CellRangeAddressList(firstRow, lastRow, col, col);
        XSSFDataValidation dataValidation = (XSSFDataValidation)
            dataValidationHelper.createValidation(dataValidationConstraint, rangeAddressList);
        dataValidation.setShowErrorBox(false);
        sheet.addValidationData(dataValidation);
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
