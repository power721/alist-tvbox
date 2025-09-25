package cn.har01d.alist_tvbox.live.model;

import com.qq.tars.protocol.tars.TarsInputStream;
import com.qq.tars.protocol.tars.TarsOutputStream;
import com.qq.tars.protocol.tars.TarsStructBase;

public class HuyaGetTokenResp extends TarsStructBase {

    private String url = "";
    private String cdnType = "";
    private String streamName = "";
    private long presenterUid = 0L;
    private String antiCode = "";
    private String sTime = "";
    private String flvAntiCode = "";
    private String hlsAntiCode = "";

    public HuyaGetTokenResp() {
    }

    public HuyaGetTokenResp(String url,
                            String cdnType,
                            String streamName,
                            long presenterUid,
                            String antiCode,
                            String sTime,
                            String flvAntiCode,
                            String hlsAntiCode) {
        this.url = url;
        this.cdnType = cdnType;
        this.streamName = streamName;
        this.presenterUid = presenterUid;
        this.antiCode = antiCode;
        this.sTime = sTime;
        this.flvAntiCode = flvAntiCode;
        this.hlsAntiCode = hlsAntiCode;
    }

    @Override
    public void writeTo(TarsOutputStream os) {
        os.write(this.url, 0);
        os.write(this.cdnType, 1);
        os.write(this.streamName, 2);
        os.write(this.presenterUid, 3);
        os.write(this.antiCode, 4);
        os.write(this.sTime, 5);
        os.write(this.flvAntiCode, 6);
        os.write(this.hlsAntiCode, 7);
    }

    @Override
    public void readFrom(TarsInputStream is) {
        this.url = is.read(this.url, 0, false);
        this.cdnType = is.read(this.cdnType, 1, false);
        this.streamName = is.read(this.streamName, 2, false);
        this.presenterUid = is.read(this.presenterUid, 3, false);
        this.antiCode = is.read(this.antiCode, 4, false);
        this.sTime = is.read(this.sTime, 5, false);
        this.flvAntiCode = is.read(this.flvAntiCode, 6, false);
        this.hlsAntiCode = is.read(this.hlsAntiCode, 7, false);
    }

    // Getters and Setters
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getCdnType() { return cdnType; }
    public void setCdnType(String cdnType) { this.cdnType = cdnType; }

    public String getStreamName() { return streamName; }
    public void setStreamName(String streamName) { this.streamName = streamName; }

    public long getPresenterUid() { return presenterUid; }
    public void setPresenterUid(long presenterUid) { this.presenterUid = presenterUid; }

    public String getAntiCode() { return antiCode; }
    public void setAntiCode(String antiCode) { this.antiCode = antiCode; }

    public String getsTime() { return sTime; }
    public void setsTime(String sTime) { this.sTime = sTime; }

    public String getFlvAntiCode() { return flvAntiCode; }
    public void setFlvAntiCode(String flvAntiCode) { this.flvAntiCode = flvAntiCode; }

    public String getHlsAntiCode() { return hlsAntiCode; }
    public void setHlsAntiCode(String hlsAntiCode) { this.hlsAntiCode = hlsAntiCode; }

    @Override
    public String toString() {
        return "HuyaGetTokenResp{" +
                "url='" + url + '\'' +
                ", cdnType='" + cdnType + '\'' +
                ", streamName='" + streamName + '\'' +
                ", presenterUid=" + presenterUid +
                ", antiCode='" + antiCode + '\'' +
                ", sTime='" + sTime + '\'' +
                ", flvAntiCode='" + flvAntiCode + '\'' +
                ", hlsAntiCode='" + hlsAntiCode + '\'' +
                '}';
    }
}
