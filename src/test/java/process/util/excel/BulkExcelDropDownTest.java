package process.util.excel;

import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The job template's drop-downs (downloadSourceJobTemplateFile). A list longer than Excel's 255 characters -- a platform
 * administrator's tasks of every workspace, or one workspace's many -- made POI refuse the whole template ("must be less
 * than or equal to 255 characters") and the download answered 400. Such a list now lives on a hidden sheet and the
 * drop-down names its range; a short one stays in the cell as before.
 */
class BulkExcelDropDownTest {

    private final BulkExcel excel = new BulkExcel();

    @Test
    void aShortListStaysInTheCell() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("JobAdd");
            this.excel.fillDropDownValue(sheet, 1, 999, 7, new String[] {"High", "Medium", "Low"});

            DataValidationConstraint constraint = sheet.getDataValidations().get(0).getValidationConstraint();
            assertThat(constraint.getExplicitListValues()).containsExactly("High", "Medium", "Low");
            assertThat(wb.getSheet(BulkExcel.LIST_SHEET)).isNull();
        }
    }

    @Test
    void aLongListGoesOnAHiddenSheetAndTheWorkbookWrites() throws Exception {
        String[] tasks = IntStream.rangeClosed(1, 40).mapToObj(i -> "Patient 360 source task number " + i).toArray(String[]::new);
        String[] frequency = {"Mint", "Hr", "Daily"};
        assertThat(BulkExcel.explicitLength(tasks)).isGreaterThan(BulkExcel.EXPLICIT_LIST_LIMIT);
        byte[] written;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("JobAdd");
            this.excel.fillDropDownValue(sheet, 1, 999, 1, tasks);
            this.excel.fillDropDownValue(sheet, 1, 999, 5, frequency);
            this.excel.fillDropDownValue(sheet, 1, 999, 2, tasks);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            written = out.toByteArray();
        }
        try (XSSFWorkbook read = new XSSFWorkbook(new ByteArrayInputStream(written))) {
            XSSFSheet lists = read.getSheet(BulkExcel.LIST_SHEET);
            assertThat(lists).isNotNull();
            assertThat(read.isSheetHidden(read.getSheetIndex(lists))).isTrue();
            assertThat(lists.getRow(0).getCell(0).getStringCellValue()).isEqualTo(tasks[0]);
            assertThat(lists.getRow(39).getCell(0).getStringCellValue()).isEqualTo(tasks[39]);
            assertThat(lists.getRow(0).getCell(1).getStringCellValue()).as("the second long list, its own column").isEqualTo(tasks[0]);

            List<? extends DataValidation> validations = read.getSheet("JobAdd").getDataValidations();
            assertThat(validations).hasSize(3);
            assertThat(validations).extracting(v -> v.getValidationConstraint().getFormula1())
                .contains("'_lists'!$A$1:$A$40", "'_lists'!$B$1:$B$40");
            assertThat(validations).anySatisfy(v -> assertThat(v.getValidationConstraint().getExplicitListValues())
                .containsExactly(frequency));
        }
    }
}
