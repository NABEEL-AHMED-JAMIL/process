package process.engine.parser;

import com.google.gson.Gson;

import javax.xml.bind.annotation.XmlRootElement;

/**
 * @author Nabeel Ahmed
 */
@XmlRootElement
public class FileAnalyticsData {

    private String id;
    private String imp;
    private Device device;

    public FileAnalyticsData() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getImp() {
        return imp;
    }

    public void setImp(String imp) {
        this.imp = imp;
    }

    public Device getDevice() {
        return device;
    }

    public void setDevice(Device device) {
        this.device = device;
    }

    public class Device {

        private Integer ua;
        private String ip;

        public Device() {
        }

        public Integer getUa() {
            return ua;
        }

        public void setUa(Integer ua) {
            this.ua = ua;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        @Override
        public String toString() {
            return new Gson().toJson(this);
        }
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
