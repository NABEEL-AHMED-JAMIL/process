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
 * @author Nabeel Ahmed
 */
@Component
public class BulkExcel {

    private Logger logger = LogManager.getLogger(BulkExcel.class);

    private String STATIC_SHEET = "ListSheet";
    private String CATEGORIES = "Categories";
    private String FORMULATE = "INDIRECT($F$2)";
    private String WEEKLY = "Weekly";
    private String DAILY = "Daily";

    public XSSFWorkbook wb;
    private XSSFSheet sheet;
    private final String FOUNT_NAME = "Calibre";

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

    /**
     * This method use to fill the header in excel file
     * @param rowCount
     * @param HEADER_FILED_BATCH_FILE
     * */
    public void fillBulkHeader(Integer rowCount, String[] HEADER_FILED_BATCH_FILE) {
        Row header = this.sheet.createRow(rowCount);
        CellStyle style = this.cellHeadingBackgroundColorStyle(IndexedColors.GREY_25_PERCENT.getIndex());
        int start = 0;
        int index = 0;
        for (int i=start; i<HEADER_FILED_BATCH_FILE.length; i++) {
            fillHeading(index, header, style, HEADER_FILED_BATCH_FILE[i]);
            index = index+1;
        }
    }

    /**
     * This method use to fill the body in excel file
     * @param data
     * @param rowCount
     * */
    public void fillBulkBody(List<String> data, Integer rowCount) {
        Row body = this.sheet.createRow(rowCount);
        for(int i=0; i<data.size(); i++) {
            this.fillCellValue(i, body, data.get(i));
        }
    }

    /**
     * This method use to fill the HeadingBackgroundColorStyle in excel file
     * @param backgroundColor
     * @return CellStyle
     * */
    public CellStyle cellHeadingBackgroundColorStyle(short backgroundColor) {
        CellStyle style = this.wb.createCellStyle();
        style.setFont(this.getFont());
        style.setFillForegroundColor(backgroundColor);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER_SELECTION);
        return style;
    }

    /**
     * This method use to fill get the font for excel file
     * @return Font
     * */
    public Font getFont() {
        Font font = this.wb.createFont();
        font.setFontName(FOUNT_NAME);
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        return font;
    }

    /**
     * This method use to fill Heading for excel file
     * @param fillCellCount
     * @param title
     * @param style
     * @param value
     * */
    public void fillHeading(Integer fillCellCount, Row title, CellStyle style, String value) {
        Cell cell = title.createCell(fillCellCount);
        cell.setCellStyle(style);
        this.getSheet().setColumnWidth(cell.getColumnIndex(), 30*255);
        cell.setCellValue(value);
    }

    /**
     * This method use to fill cell value in excel file
     * @param fillCellCount
     * @param body
     * @param value
     * */
    public void fillCellValue(Integer fillCellCount, Row body, String value) {
        Cell cell = body.createCell(fillCellCount);
        cell.setCellValue(value);
    }

    /**
     * This method use to get fetch the detail
     * @param row
     * @param index
     * */
    public String getCellDetail(Row row, Integer index) {
        Cell currentCell = row.getCell(index, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        currentCell.setCellType(CellType.STRING);
        return currentCell.getStringCellValue();
    }

    /**
     * The fillDropDownValue use to fill the list in the cell
     * @param sheet
     * @param row
     * @param col
     * @param dropList
     */
    public void fillDropDownValue(XSSFSheet sheet, Integer row, Integer col, String[] dropList) {
        XSSFDataValidationHelper dataValidationHelper = new XSSFDataValidationHelper(sheet);
        XSSFDataValidationConstraint dataValidationConstraint = (XSSFDataValidationConstraint)
                dataValidationHelper.createExplicitListConstraint(dropList);
        CellRangeAddressList rangeAddressList = new CellRangeAddressList(row, row, col, col);
        XSSFDataValidation dataValidation = (XSSFDataValidation)
                dataValidationHelper.createValidation(dataValidationConstraint, rangeAddressList);
        dataValidation.setShowErrorBox(false);
        sheet.addValidationData(dataValidation);
    }

    /**
     * The fillDropDownValueV2 use to fill the list in the cell
     * @param sheet
     * @param row
     * @param col
     * @param formulaList
     */
    public void fillDropDownValueV2(XSSFSheet sheet, Integer row, Integer col, String formulaList) {
        //data validations
        DataValidationHelper dvHelper = sheet.getDataValidationHelper();
        DataValidationConstraint dvConstraint = dvHelper.createFormulaListConstraint(formulaList);
        CellRangeAddressList addressList = new CellRangeAddressList(row, row, col, col);
        DataValidation validation = dvHelper.createValidation(dvConstraint, addressList);
        sheet.addValidationData(validation);
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}