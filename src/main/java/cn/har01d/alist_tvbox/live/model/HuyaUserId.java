package cn.har01d.alist_tvbox.live.model;

import com.qq.tars.protocol.tars.TarsInputStream;
import com.qq.tars.protocol.tars.TarsOutputStream;
import com.qq.tars.protocol.tars.TarsStructBase;

public class HuyaUserId extends TarsStructBase {
    private long lUid = 0L;
    private String sGuid = "";
    private String sToken = "";
    private String sHuYaUA = "";
    private String sCookie = "";
    private int iTokenType = 0;
    private String sDeviceInfo = "";
    private String sQIMEI = "";

    @Override
    public void writeTo(TarsOutputStream os) {
        os.write(this.lUid, 0);
        os.write(this.sGuid, 1);
        os.write(this.sToken, 2);
        os.write(this.sHuYaUA, 3);
        os.write(this.sCookie, 4);
        os.write(this.iTokenType, 5);
        os.write(this.sDeviceInfo, 6);
        os.write(this.sQIMEI, 7);
    }

    @Override
    public void readFrom(TarsInputStream is) {
        this.lUid = is.read(this.lUid, 0, false);
        this.sGuid = is.read(this.sGuid, 1, false);
        this.sToken = is.read(this.sToken, 2, false);
        this.sHuYaUA = is.read(this.sHuYaUA, 3, false);
        this.sCookie = is.read(this.sCookie, 4, false);
        this.iTokenType = is.read(this.iTokenType, 5, false);
        this.sDeviceInfo = is.read(this.sDeviceInfo, 6, false);
        this.sQIMEI = is.read(this.sQIMEI, 7, false);
    }

    public long getLUid() {
        return lUid;
    }

    public void setLUid(long lUid) {
        this.lUid = lUid;
    }

    public String getSGuid() {
        return sGuid;
    }

    public void setSGuid(String sGuid) {
        this.sGuid = sGuid;
    }

    public String getSToken() {
        return sToken;
    }

    public void setSToken(String sToken) {
        this.sToken = sToken;
    }

    public String getSHuYaUA() {
        return sHuYaUA;
    }

    public void setSHuYaUA(String sHuYaUA) {
        this.sHuYaUA = sHuYaUA;
    }

    public String getSCookie() {
        return sCookie;
    }

    public void setSCookie(String sCookie) {
        this.sCookie = sCookie;
    }

    public int getITokenType() {
        return iTokenType;
    }

    public void setITokenType(int iTokenType) {
        this.iTokenType = iTokenType;
    }

    public String getSDeviceInfo() {
        return sDeviceInfo;
    }

    public void setSDeviceInfo(String sDeviceInfo) {
        this.sDeviceInfo = sDeviceInfo;
    }

    public String getSQIMEI() {
        return sQIMEI;
    }

    public void setSQIMEI(String sQIMEI) {
        this.sQIMEI = sQIMEI;
    }
}
