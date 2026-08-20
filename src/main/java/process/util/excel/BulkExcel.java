package process.util.excel;

import java.util.List;
import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Component;

@Component
public class BulkExcel {

    private Logger logger = LogManager.getLogger(BulkExcel.class);

    public XSSFWorkbook wb;
    private XSSFSheet sheet;

    public XSSFWorkbook getWb() {
        return wb;
    }
    public void setWb(XSSFWorkbook wb) {
        this.wb = wb;
    }

    public XSSFSheet getSheet() {
        return sheet;
    }
    public void setSheet(XSSFSheet sheet) {
        this.sheet = sheet;
    }

    public void fillBulkHeader(Integer rowCount, String[] HEADER_FILED_BATCH_FILE) {
        Row header = this.sheet.createRow(rowCount);
        CellStyle style = this.cellHeadingBackgroundColorStyle(IndexedColors.GREY_25_PERCENT.getIndex());
        int start = 0; int index = 0;
        for (int i=start; i<HEADER_FILED_BATCH_FILE.length; i++) {
            fillHeading(index, header, style, HEADER_FILED_BATCH_FILE[i]);
            index = index+1;
        }
    }

    public void fillBulkBody(List<String> data, Integer rowCount) {
        Row body = this.sheet.createRow(rowCount);
        for(int i=0; i<data.size(); i++) {
            this.fillCellValue(i, body, data.get(i));
        }
    }

    public CellStyle cellHeadingBackgroundColorStyle(short backgroundColor) {
        CellStyle style = this.wb.createCellStyle();
        style.setFont(this.getFont());
        style.setFillForegroundColor(backgroundColor);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER_SELECTION);
        return style;
    }

    public Font getFont() {
        Font font = this.wb.createFont();
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

    private final DataFormatter cellFormatter = new DataFormatter();

    public String getCellDetail(Row row, Integer index) {
        Cell currentCell = row.getCell(index, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        return this.cellFormatter.formatCellValue(currentCell).trim();
    }

    public void fillDropDownValue(XSSFSheet sheet, Integer row, Integer col, String[] dropList) {
        this.fillDropDownValue(sheet, row, row, col, dropList);
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
