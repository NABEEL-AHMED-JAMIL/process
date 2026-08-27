package process.model.enums;

/**
 * @author Nabeel Ahmed
 * */
public enum StorageProvider {

    MINIO,
    S3,
    AZURE,
    FTP,
    FTPS;

    public boolean isObjectStore() {
        return this == MINIO || this == S3 || this == AZURE;
    }

    public boolean isFtpFamily() {
        return this == FTP || this == FTPS;
    }

}
