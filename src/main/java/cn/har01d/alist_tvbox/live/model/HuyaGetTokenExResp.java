package cn.har01d.alist_tvbox.live.model;

import com.qq.tars.protocol.tars.TarsInputStream;
import com.qq.tars.protocol.tars.TarsOutputStream;
import com.qq.tars.protocol.tars.TarsStructBase;

public class HuyaGetTokenExResp extends TarsStructBase {
    private String sFlvToken = "";
    private int iExpireTime = 0;

    @Override
    public void writeTo(TarsOutputStream os) {
        os.write(this.sFlvToken, 0);
        os.write(this.iExpireTime, 1);
    }

    @Override
    public void readFrom(TarsInputStream is) {
        this.sFlvToken = is.read(this.sFlvToken, 0, false);
        this.iExpireTime = is.read(this.iExpireTime, 1, false);
    }

    public String getSFlvToken() {
        return sFlvToken;
    }

    public void setSFlvToken(String sFlvToken) {
        this.sFlvToken = sFlvToken;
    }

    public int getIExpireTime() {
        return iExpireTime;
    }

    public void setIExpireTime(int iExpireTime) {
        this.iExpireTime = iExpireTime;
    }

    @Override
    public String toString() {
        return "HuyaGetTokenExResp{" +
                "sFlvToken='" + sFlvToken + '\'' +
                ", iExpireTime=" + iExpireTime +
                '}';
    }
}
