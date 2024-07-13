package cn.har01d.alist_tvbox.youtube;

import com.alibaba.fastjson.JSONObject;
import com.github.kiulian.downloader.model.videos.formats.Format;

public class LiveFormat extends Format {
    protected LiveFormat(String url) {
        super(new JSONObject().fluentPut("url", url).fluentPut("itag", 5), true, "");
    }

    @Override
    public String type() {
        return VIDEO;
    }
}
