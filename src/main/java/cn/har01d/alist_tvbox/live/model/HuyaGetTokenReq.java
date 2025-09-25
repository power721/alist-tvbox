package cn.har01d.alist_tvbox.live.model;

import com.qq.tars.protocol.tars.TarsInputStream;
import com.qq.tars.protocol.tars.TarsOutputStream;
import com.qq.tars.protocol.tars.TarsStructBase;

public class HuyaGetTokenReq extends TarsStructBase {

    private String url = "";
    private String cdnType;
    private String streamName;
    private long presenterUid = 0L;

    public HuyaGetTokenReq() {
    }

    public HuyaGetTokenReq(String url, String cdnType, String streamName, long presenterUid) {
        this.url = url;
        this.cdnType = cdnType;
        this.streamName = streamName;
        this.presenterUid = presenterUid;
    }

    @Override
    public void writeTo(TarsOutputStream os) {
        os.write(this.url, 0);
        os.write(this.cdnType, 1);
        os.write(this.streamName, 2);
        os.write(this.presenterUid, 3);
    }

    @Override
    public void readFrom(TarsInputStream is) {
        this.url = is.read(this.url, 0, false);
        this.cdnType = is.read(this.cdnType, 1, false);
        this.streamName = is.read(this.streamName, 2, false);
        this.presenterUid = is.read(this.presenterUid, 3, false);
    }

    // Getters and setters
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getCdnType() {
        return cdnType;
    }

    public void setCdnType(String cdnType) {
        this.cdnType = cdnType;
    }

    public String getStreamName() {
        return streamName;
    }

    public void setStreamName(String streamName) {
        this.streamName = streamName;
    }

    public long getPresenterUid() {
        return presenterUid;
    }

    public void setPresenterUid(long presenterUid) {
        this.presenterUid = presenterUid;
    }

    @Override
    public String toString() {
        return "HuyaGetTokenReq{" +
                "url='" + url + '\'' +
                ", cdnType='" + cdnType + '\'' +
                ", streamName='" + streamName + '\'' +
                ", presenterUid=" + presenterUid +
                '}';
    }
}
