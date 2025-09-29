package process.efs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import com.google.gson.Gson;
import org.apache.tomcat.util.http.fileupload.FileUtils;
import process.util.exception.ExceptionUtil;
import java.io.*;

/**
 * @author Nabeel Ahmed
 */
@Component
public class EfsFileExchange extends FileHashing {

    private Logger logger = LogManager.getLogger(EfsFileExchange.class);

    private String basePathTempDire;

    public EfsFileExchange() {}

    public Boolean makeDir(String basePath) {
        try {
            basePath = this.basePathTempDire.concat(basePath);
            File finalDir = new File(basePath);
            if (!finalDir.exists()) {
                logger.info("Making New Directory at path [ " + basePath + " ]");
                return finalDir.mkdirs();
            } else {
                logger.info("Directory Already Exist At Path [ " + basePath + " ]");
                return true;
            }
        } catch (Exception ex) {
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
        }
        return false;
    }

    public void saveFile(ByteArrayOutputStream byteArrayOutputStream,
        String targetFileName) throws Exception {
        if (byteArrayOutputStream != null && byteArrayOutputStream.size() > 0) {
            try (OutputStream outputStream = new FileOutputStream(
                this.basePathTempDire.concat(targetFileName))) {
                byteArrayOutputStream.writeTo(outputStream);
            } finally {
                if (byteArrayOutputStream != null) {
                    byteArrayOutputStream.flush();
                    byteArrayOutputStream.close();
                }
            }
            logger.info("File Convert and Store into local path");
        }
    }

    public InputStream getFile(String targetFileName) throws Exception {
        return new FileInputStream(targetFileName);
    }

    public void deleteDir(String basePath) {
        try {
            File file = new File(basePath);
            if (file.exists()) {
                logger.info("Deleting Directory At Path [ " + basePath + " ]");
                FileUtils.deleteDirectory(file);
            }
        } catch (Exception ex) {
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    public void cleanDir(String basePath) {
        try {
            File file = new File(basePath);
            if (file.exists()) {
                logger.info("Cleaning Directory At Path [ " + basePath + " ]");
                FileUtils.cleanDirectory(file);
            }
        } catch (Exception ex) {
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    public String getBasePathTempDire() {
        return basePathTempDire;
    }

    public void setBasePathTempDire(String basePathTempDire) {
        this.basePathTempDire = basePathTempDire;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}