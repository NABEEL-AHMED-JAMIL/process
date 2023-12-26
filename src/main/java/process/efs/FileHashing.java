package process.efs;

import java.io.*;
import java.security.*;

/**
 * @author Nabeel Ahmed
 */
public class FileHashing {

    public FileHashing() {
    }

    /**
     * Method use to generate the hash
     * @param filePath
     * @param digest
     * @return byte[]
     * */
    public byte[] generateHash(String filePath, MessageDigest digest) throws IOException {
        try (InputStream fis = new FileInputStream(filePath)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, len);
            }
            return digest.digest();
        }
    }

    /**
     * Method use to convert the byte to hex
     * @param hash
     * @return string
     * */
    public String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
