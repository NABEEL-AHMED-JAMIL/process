package process.util.excel;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Opens a bulk upload. A file sent as .xlsx that is not one (a renamed CSV, a text file) makes POI
 * throw, and the uploads let that escape: the task page answered HTTP 500 "Some internal error
 * occurred", the job page a generic "contact support". It is a refused file like any other.
 *
 * @author Nabeel Ahmed
 */
public final class UploadedSheet {

    private static final Logger logger = LoggerFactory.getLogger(UploadedSheet.class);

    /** What the uploader is told when the file cannot be read as a spreadsheet. */
    public static final String UNREADABLE = "That file is not a readable .xlsx spreadsheet. Start from the import template.";

    private UploadedSheet() {}

    /** The workbook, or null when the file is not an .xlsx POI can read. The caller closes it. */
    public static XSSFWorkbook openOrNull(MultipartFile file) {
        try {
            return new XSSFWorkbook(file.getInputStream());
        } catch (IOException | RuntimeException ex) {
            // POI's NotOfficeXmlFileException, POIXMLException and friends are all unchecked.
            logger.info("Bulk upload is not a readable .xlsx: {}", ex.getMessage());
            return null;
        }
    }
}
