package cn.har01d.alist_tvbox.service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Downloads 115.index.zip for a published share. Implementations mount Pan115Share,
 * which auto-转存 into /alist-tvbox-temp and auto-deletes on link resolution.
 */
public interface Index115Downloader {
    void download(String shareCode, String receiveCode, Path localDest) throws IOException;
}
