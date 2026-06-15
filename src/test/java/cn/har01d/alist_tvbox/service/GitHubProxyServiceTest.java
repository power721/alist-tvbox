package cn.har01d.alist_tvbox.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubProxyServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    void readsSavedDirectProxyEntryInOrder() throws IOException {
        Path proxyFile = tempDir.resolve("github_proxy.txt");
        Files.write(proxyFile, List.of("https://gh.llkk.cc/", "", "https://github.starrlzy.cn/"));
        GitHubProxyService service = new GitHubProxyService(proxyFile);

        List<String> proxies = service.readProxyListFromFile();

        assertThat(proxies).containsExactly("https://gh.llkk.cc/", "", "https://github.starrlzy.cn/");
    }
}
