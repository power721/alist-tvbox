package cn.har01d.alist_tvbox.live.model;

import com.qq.tars.protocol.tars.TarsInputStream;
import com.qq.tars.protocol.tars.TarsOutputStream;
import com.qq.tars.protocol.tars.TarsStructBase;

public class HuyaGetTokenExReq extends TarsStructBase {
    private String sFlvUrl = "";
    private String sStreamName = "";
    private int iLoopTime = 0;
    private HuyaUserId tId = new HuyaUserId();
    private int iAppId = 66;

    @Override
    public void writeTo(TarsOutputStream os) {
        os.write(this.sFlvUrl, 0);
        os.write(this.sStreamName, 1);
        os.write(this.iLoopTime, 2);
        os.write(this.tId, 3);
        os.write(this.iAppId, 4);
    }

    @Override
    public void readFrom(TarsInputStream is) {
        this.sFlvUrl = is.read(this.sFlvUrl, 0, false);
        this.sStreamName = is.read(this.sStreamName, 1, false);
        this.iLoopTime = is.read(this.iLoopTime, 2, false);
        this.tId = (HuyaUserId) is.read(this.tId, 3, false);
        this.iAppId = is.read(this.iAppId, 4, false);
    }

    public String getSFlvUrl() {
        return sFlvUrl;
    }

    public void setSFlvUrl(String sFlvUrl) {
        this.sFlvUrl = sFlvUrl;
    }

    public String getSStreamName() {
        return sStreamName;
    }

    public void setSStreamName(String sStreamName) {
        this.sStreamName = sStreamName;
    }

    public int getILoopTime() {
        return iLoopTime;
    }

    public void setILoopTime(int iLoopTime) {
        this.iLoopTime = iLoopTime;
    }

    public HuyaUserId getTId() {
        return tId;
    }

    public void setTId(HuyaUserId tId) {
        this.tId = tId;
    }

    public int getIAppId() {
        return iAppId;
    }

    public void setIAppId(int iAppId) {
        this.iAppId = iAppId;
    }
}
