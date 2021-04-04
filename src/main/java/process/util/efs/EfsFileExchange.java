package process.util.efs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.google.gson.Gson;
import org.apache.tomcat.util.http.fileupload.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import process.util.exception.ExceptionUtil;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;

/**
 * @author Nabeel Ahmed
 */
@Component
@Scope("prototype")
public class EfsFileExchange {

    private Logger logger = LogManager.getLogger(EfsFileExchange.class);

    // path location of efs
    @Value("${storage.efsFileDire}")
    private String basePathTempDire;

    public EfsFileExchange() {}

    /**
     * This method use to create the directory in the efs
     * if the folder already exist its not create
     * @param basePath
     * @return boolean true|false
     * */
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

    /**
     * This saveFile method use to save the file into efs with the target path
     * method get the bytearray-stream and target file name
     * @param byteArrayOutputStream
     * @param targetFileName
     * @throws Exception
     * */
    @Async
    public void saveFile(ByteArrayOutputStream byteArrayOutputStream, String targetFileName) throws Exception {
        if (byteArrayOutputStream != null && byteArrayOutputStream.size() > 0) {
            try (OutputStream outputStream = new FileOutputStream(this.basePathTempDire.concat(targetFileName))) {
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

    /**
     * Method use to read the file from efs with the given file path
     * @param targetFileName
     * @throws Exception
     * @return InputStream
     * */
    public InputStream getFile(String targetFileName) throws Exception {
        return new FileInputStream(targetFileName);
    }

    /**
     * This deleteDir method use to delete the folder
     * @param basePath
     * */
    @Async
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

    /**
     * This cleanDir method use to clean the directory
     * mean the directory remain there but file delete
     * @param basePath
     * */
    @Async
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

    /***
     * this cleanEfsOldFiles remove all the old file from the efs
     * base on date
     * */
    @Async
    @Scheduled(cron = "00 10 12 * * ?")
    public void clearEfsOldFiles() {
        try {
            Date CURRENT_DATE = new Date();
            logger.info("CRON clearEfsOldFiles Started  :" + CURRENT_DATE);
            Calendar date2DaysBack = Calendar.getInstance();
            date2DaysBack.add(Calendar.DATE, -3);
            SimpleDateFormat df = new SimpleDateFormat("YYYY-MM-dd");
            File maindir = new File(basePathTempDire);
            File arr[] = null;
            if(maindir.exists() && maindir.isDirectory()) {
                try {
                    // array for files and sub-directories of directory pointed by maindir
                    arr = maindir.listFiles();
                    Arrays.sort(arr, Comparator.comparingLong(File::lastModified).reversed());
                    this.recursivePrint(arr,0,0,df,date2DaysBack);
                } catch (Exception ex) {
                    logger.error("Error In clearEfsOldFiles " + ExceptionUtil.getRootCauseMessage(ex));
                }
            }
        } catch (Exception ex) {
            logger.error("Error In clearEfsOldFiles " + ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    private void recursivePrint(File[] arr,int index,int level,SimpleDateFormat df,Calendar date2DaysBack) {
        // terminate condition
        if(arr!=null && index == arr.length){ return; }
        // tabs for internal levels
        for (int i = 0; i < level; i++) {
            System.out.print("\t");
        }
        // for files
        if(arr!=null && arr[index].isFile()) {
            this.deleteFileOrFolderIfItsOld(arr[index], df, date2DaysBack);
        } else if(arr!=null && arr[index].isDirectory()) {  // for sub-directories
            deleteFileOrFolderIfItsOld(arr[index], df, date2DaysBack);
            // recursion for sub-directories
            this.recursivePrint(arr[index].listFiles(), 0, level + 1,df,date2DaysBack);
        }
        this.recursivePrint(arr,++index, level,df,date2DaysBack);
    }

    private void deleteFileOrFolderIfItsOld(File fileOrFolder, SimpleDateFormat df, Calendar date2DaysBack) {
        BasicFileAttributes attr;
        try {
            attr = Files.readAttributes(fileOrFolder.toPath(), BasicFileAttributes.class);
            FileTime fileTime = attr.creationTime();
            String folderName = fileOrFolder.getName();
            if (fileTime.toInstant().isBefore(date2DaysBack.toInstant())) {
                System.out.println("+++++++++++++++clearEfsOldFiles  "+folderName + " is old folder or file!");
                if (fileOrFolder.isDirectory()) {
                    org.apache.commons.io.FileUtils.cleanDirectory(fileOrFolder);
                    // Then, remove the folder
                    org.apache.commons.io.FileUtils.deleteDirectory(fileOrFolder);
                }
                logger.info(folderName+" deleted");
            } else {
                logger.info(folderName + " is NOt old!");
            }
        } catch (Exception ex) {
            logger.error("Error In deleteFileOrFolderIfItsOld " + ExceptionUtil.getRootCauseMessage(ex));
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
