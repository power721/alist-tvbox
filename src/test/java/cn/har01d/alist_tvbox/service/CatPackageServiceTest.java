package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.CatUploadResult;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatPackageServiceTest {

    @TempDir
    Path tempDir;

    Path webCat;
    Path dataCat;
    CatPackageService service;

    @BeforeEach
    void setUp() {
        webCat = tempDir.resolve("www/cat");
        dataCat = tempDir.resolve("data/cat");
        service = new CatPackageService(new ObjectMapper()) {
            @Override
            Path webRoot() {
                return webCat;
            }

            @Override
            Path dataRoot() {
                return dataCat;
            }
        };
    }

    private MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    @Test
    void uploadsCustomSpiderToBothLocationsAndManifest() throws IOException {
        CatUploadResult result = service.upload(file("demo_open.js", "var a=1;export{}".getBytes()), false);

        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).path()).isEqualTo("custom/demo_open.js");
        assertThat(result.entries().get(0).overwritten()).isFalse();
        assertThat(webCat.resolve("custom/demo_open.js")).exists();
        assertThat(dataCat.resolve("custom/demo_open.js")).exists();
        assertThat(webCat.resolve("custom/spiders.json")).content()
                .contains("\"key\" : \"demo\"")
                .contains("\"name\" : \"demo\"")
                .contains("\"file\" : \"demo_open.js\"")
                .contains("\"md5\" :");
    }

    @Test
    void uploadsChineseFilenameSpider() throws IOException {
        // 生态分享的爬虫文件名常含中文/全角括号
        CatUploadResult result = service.upload(file("次元成（猫源）.js", "var a=1;export{}".getBytes()), false);

        assertThat(result.entries().get(0).path()).isEqualTo("custom/次元成（猫源）.js");
        assertThat(webCat.resolve("custom/次元成（猫源）.js")).exists();
        assertThat(webCat.resolve("custom/spiders.json")).content()
                .contains("\"file\" : \"次元成（猫源）.js\"");

        assertThat(service.requireFile("custom/次元成（猫源）.js")).exists();
        // 空格文件名同样放行
        service.upload(file("my spider.js", "x".getBytes()), false);
        assertThat(webCat.resolve("custom/my spider.js")).exists();
    }

    @Test
    void undefinedNameFallsBackToKey() throws IOException {
        // el-upload 会把前端 undefined 序列化成字符串
        service.upload(file("demo_open.js", "var a=1;export{}".getBytes()), false, "undefined");

        assertThat(webCat.resolve("custom/spiders.json")).content()
                .contains("\"name\" : \"demo\"")
                .doesNotContain("undefined");
    }

    @Test
    void customSpiderNameFromUploadParameter() throws IOException {
        service.upload(file("my_source_open.js", "var a=1;export{}".getBytes()), false, "我的源");

        assertThat(webCat.resolve("custom/spiders.json")).content()
                .contains("\"key\" : \"my_source\"")
                .contains("\"name\" : \"我的源\"")
                .contains("\"file\" : \"my_source_open.js\"");
    }

    @Test
    void reuploadCustomSpiderUpsertsManifest() throws IOException {
        service.upload(file("demo_open.js", "v1".getBytes()), false, "名称A");
        CatUploadResult result = service.upload(file("demo_open.js", "v2".getBytes()), false, "名称B");

        assertThat(result.entries().get(0).overwritten()).isTrue();
        String manifest = Files.readString(webCat.resolve("custom/spiders.json"));
        assertThat(manifest).contains("\"name\" : \"名称B\"").doesNotContain("名称A");
        // 同 key upsert 不产生重复条目
        assertThat(manifest.split("\"key\"", -1).length - 1).isEqualTo(1);
        assertThat(webCat.resolve("custom/demo_open.js")).hasContent("v2");
    }

    @Test
    void rejectsIllegalNamesAndNonSpiderFiles() {
        assertThatThrownBy(() -> service.upload(file("../evil.js", "x".getBytes()), false))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.upload(file("..", "x".getBytes()), false))
                .isInstanceOf(BadRequestException.class);
        // 整包替换已下线:三件套与任意根文件拒绝
        assertThatThrownBy(() -> service.upload(file("index.js", "x".getBytes()), false))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.upload(file("index.config.js", "x".getBytes()), false))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.upload(file("index.config.js.md5", "abc".getBytes()), false))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.upload(file("data.json", "[]".getBytes()), false))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectsZipWithoutAutoExtract() {
        assertThatThrownBy(() -> service.upload(file("deps.zip", "x".getBytes()), false))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("自动解压");
    }

    @Test
    void extractsSpiderAndDependencyZip() throws IOException {
        byte[] zip = zipOf(
                new String[]{"custom/demo_open.js", "custom/lib/ali.js", "lib/cat.js"},
                new String[]{"spider", "dep", "catlib"});
        var result = service.upload(file("deps.zip", zip), true);

        assertThat(webCat.resolve("custom/demo_open.js")).hasContent("spider");
        assertThat(dataCat.resolve("custom/lib/ali.js")).hasContent("dep");
        assertThat(webCat.resolve("lib/cat.js")).hasContent("catlib");
        // 仅 custom/ 顶层 .js 登记清单,依赖文件不登记
        assertThat(webCat.resolve("custom/spiders.json")).content()
                .contains("demo_open.js")
                .doesNotContain("ali.js");
        assertThat(result.entries()).extracting("path")
                .containsExactly("custom/demo_open.js", "custom/lib/ali.js", "lib/cat.js");
    }

    @Test
    void zipWithRootEntriesRejectedAtomically() throws IOException {
        byte[] zip = zipOf(new String[]{"custom/demo_open.js", "index.js"}, new String[]{"spider", "runtime"});

        assertThatThrownBy(() -> service.upload(file("bundle.zip", zip), true))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("custom/");

        // 两阶段校验:整包条目零落盘
        assertThat(webCat.resolve("custom/demo_open.js")).doesNotExist();
        assertThat(webCat.resolve("index.js")).doesNotExist();
    }

    @Test
    void zipWithSystemManifestRejected() throws IOException {
        byte[] zip = zipOf(new String[]{"custom/spiders.json"}, new String[]{"[]"});
        assertThatThrownBy(() -> service.upload(file("bundle.zip", zip), true))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("系统维护");
        assertThat(webCat.resolve("custom/spiders.json")).doesNotExist();
    }

    @Test
    void deletingCustomSpiderRemovesManifestEntry() throws IOException {
        service.upload(file("demo_open.js", "x".getBytes()), false, null);
        service.upload(file("other.js", "y".getBytes()), false, null);

        service.delete("custom/demo_open.js");

        assertThat(webCat.resolve("custom/demo_open.js")).doesNotExist();
        assertThat(webCat.resolve("custom/spiders.json")).content().doesNotContain("demo_open.js").contains("other.js");
    }

    @Test
    void deletingDependencyFileAllowed() throws IOException {
        byte[] zip = zipOf(new String[]{"custom/lib/ali.js"}, new String[]{"dep"});
        service.upload(file("deps.zip", zip), true);

        service.delete("custom/lib/ali.js");

        assertThat(webCat.resolve("custom/lib/ali.js")).doesNotExist();
    }

    @Test
    void deleteGuards() throws IOException {
        service.upload(file("demo_open.js", "x".getBytes()), false, null);
        assertThatThrownBy(() -> service.delete("custom/spiders.json"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("系统维护");
        assertThatThrownBy(() -> service.delete("index.js"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.delete("custom/missing.js"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete(".."))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void requireFileValidatesScopeAndExistence() throws IOException {
        byte[] zip = zipOf(new String[]{"custom/demo_open.js", "lib/cat.js"}, new String[]{"spider", "catlib"});
        service.upload(file("deps.zip", zip), true);

        assertThat(Files.readString(service.requireFile("custom/demo_open.js"))).isEqualTo("spider");
        assertThat(Files.readString(service.requireFile("lib/cat.js"))).isEqualTo("catlib");
        assertThatThrownBy(() -> service.requireFile("custom/spiders.json"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("系统维护");
        assertThatThrownBy(() -> service.requireFile("index.js"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.requireFile("custom/missing.js"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.requireFile("../evil.js"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void listShowsCustomAndLibFilesOnly() throws IOException {
        byte[] zip = zipOf(new String[]{"custom/demo_open.js", "lib/cat.js"}, new String[]{"spider", "catlib"});
        service.upload(file("deps.zip", zip), true);
        // 覆盖层根下的无关文件与系统清单均不出现在列表
        Files.createDirectories(dataCat);
        Files.write(dataCat.resolve("stray.txt"), "x".getBytes(StandardCharsets.UTF_8));

        var result = service.list();

        assertThat(result.files()).extracting("path")
                .containsExactly("custom/demo_open.js", "lib/cat.js");
    }

    private static byte[] zipOf(String[] names, String[] contents) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (int i = 0; i < names.length; i++) {
                zos.putNextEntry(new ZipEntry(names[i]));
                zos.write(contents[i].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
