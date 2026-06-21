package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.storage.Pan115Share;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Mounts the published share as a Pan115Share storage and streams 115.index.zip
 * through the bundled AList download endpoint. Pan115Share auto-转存 the file into
 * /alist-tvbox-temp to resolve the link and auto-deletes it afterwards.
 *
 * Mount/enable mirrors AListAliasService: accountService.login() for the alist
 * admin token, a reserved storage id, saveStorage + enableStorage.
 */
@Slf4j
public class AListIndex115Downloader implements Index115Downloader {
    private static final int STORAGE_ID = 7000;
    private static final int SHARE_TYPE_115 = 8;
    private static final String MOUNT_NAME = "index115";
    private static final String FILE_NAME = "115.index.zip";

    private final AListLocalService aListLocalService;
    private final ShareService shareService;
    private final AccountService accountService;

    public AListIndex115Downloader(AListLocalService aListLocalService,
                                   ShareService shareService,
                                   AccountService accountService) {
        this.aListLocalService = aListLocalService;
        this.shareService = shareService;
        this.accountService = accountService;
    }

    @Override
    public void download(String shareCode, String receiveCode, Path localDest) throws IOException {
        aListLocalService.validateAListStatus();

        Share share = new Share();
        share.setId(STORAGE_ID);
        share.setType(SHARE_TYPE_115); // mount path: /我的115分享/<name>
        share.setPath(MOUNT_NAME);
        share.setShareId(shareCode);
        share.setPassword(receiveCode);
        // folderId defaults to "root"

        Pan115Share storage = new Pan115Share(share);
        String token = accountService.login();
        aListLocalService.saveStorage(storage);
        String error = shareService.enableStorage(STORAGE_ID, token);
        if (error != null) {
            throw new IOException("enable 115 share storage failed: " + error);
        }

        URI uri;
        try {
            uri = new URI("http", null, "127.0.0.1", aListLocalService.getInternalPort(),
                    "/d" + storage.getPath() + "/" + FILE_NAME, null, null);
        } catch (java.net.URISyntaxException e) {
            throw new IOException("build download uri failed", e);
        }
        streamToFile(uri.toURL(), localDest, token);
        log.info("downloaded {} ({} bytes) from share {}", FILE_NAME, Files.size(localDest), shareCode);
    }

    private void streamToFile(URL url, Path dest, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(300000);
        conn.setInstanceFollowRedirects(true);
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", token);
        }
        try (InputStream in = conn.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
    }
}
