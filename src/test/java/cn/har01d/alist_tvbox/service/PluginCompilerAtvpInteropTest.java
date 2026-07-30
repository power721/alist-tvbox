package cn.har01d.alist_tvbox.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.NamedParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PluginCompilerAtvpInteropTest {
    private final PluginCompilerService service = new PluginCompilerService();

    @TempDir
    private Path tempDir;

    @Test
    void generatedSelfPackageShouldDecryptWithAtvpSelfKeyring() throws Exception {
        Assumptions.assumeTrue(pythonHasAtvpDependencies(), "Python Atvp dependencies are not installed");

        KeyPair keyPair = generateKeyPair();
        String source = """
                from base.spider import Spider

                class Spider(Spider):
                    def init(self, extend=""):
                        self.extend = extend
                        return "inner-init-ok"

                    def getName(self):
                        return "Interop"

                    def playerContent(self, flag, id, vipFlags):
                        return {"parse": 0, "url": "https://example.invalid/video.mp4", "header": {"X-Test": "self"}}
                """;
        PluginCompilerService.CompileResponse response = service.compileSecspider(
                new PluginCompilerService.CompileRequest(
                        "Interop",
                        7,
                        "",
                        "interop-self",
                        "interop-kid",
                        source,
                        pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                        pem("PUBLIC KEY", keyPair.getPublic().getEncoded()),
                        "interop-master-secret"
                )
        );

        Path atvp = tempDir.resolve("Atvp.py");
        String atvpText = Files.readString(Path.of("src/main/resources/static/Atvp.py"), StandardCharsets.UTF_8);
        atvpText = replacePythonListAssignment(atvpText, "_self_public_key_chunks", pyList(response.publicKeyChunks()));
        atvpText = replacePythonListAssignment(atvpText, "_self_master_secret_chunks", pyList(response.masterSecretChunks()));
        Files.writeString(atvp, atvpText, StandardCharsets.UTF_8);

        Path packageFile = tempDir.resolve("package.txt");
        Files.writeString(packageFile, response.packageText(), StandardCharsets.UTF_8);
        Path runner = tempDir.resolve("run_atvp_decrypt.py");
        Files.writeString(runner, runnerScript(atvp, packageFile), StandardCharsets.UTF_8);

        ProcessResult result = runPython(runner);
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        assertThat(result.stdout()).contains("DECRYPT_OK");
        assertThat(result.stdout()).contains("Atvp secspider loader selected: self");
        assertThat(result.stdout()).contains("RUNTIME_INIT inner-init-ok");
        assertThat(result.stdout()).contains("RUNTIME_NAME Interop");
        assertThat(result.stdout()).contains("RUNTIME_PLAYER");
        assertThat(result.stdout()).contains("https://example.invalid/video.mp4");
    }

    private boolean pythonHasAtvpDependencies() throws Exception {
        String script = "import Crypto, requests, lxml\nfrom Crypto.Signature import eddsa\n";
        ProcessBuilder builder = new ProcessBuilder("python", "-c", script)
                .directory(Path.of(".").toFile())
                .redirectErrorStream(true);
        addPythonPath(builder.environment());
        Process process = builder.start();
        return process.waitFor() == 0;
    }

    private ProcessResult runPython(Path runner) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("python", runner.toString())
                .directory(Path.of(".").toFile());
        addPythonPath(builder.environment());
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.waitFor(), stdout, stderr);
    }

    private void addPythonPath(Map<String, String> environment) {
        Path pydeps = Path.of("work", "pydeps").toAbsolutePath();
        if (!Files.isDirectory(pydeps)) {
            return;
        }
        String existing = environment.getOrDefault("PYTHONPATH", "");
        environment.put("PYTHONPATH", existing.isBlank() ? pydeps.toString() : pydeps + ";" + existing);
    }

    private String runnerScript(Path atvp, Path packageFile) {
        return """
                import importlib.util
                import base64
                import sys
                import json
                import types

                base_mod = types.ModuleType("base")
                spider_mod = types.ModuleType("base.spider")
                class BaseSpider:
                    def init(self, extend=""):
                        self.extend = extend
                        return None
                    def log(self, *args):
                        print(*args)
                spider_mod.Spider = BaseSpider
                sys.modules["base"] = base_mod
                sys.modules["base.spider"] = spider_mod

                spec = importlib.util.spec_from_file_location("atvp_interop", r"%s")
                mod = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(mod)
                spider = mod.Spider()
                package_path = r"%s"
                plain = spider._decrypt_secspider_source(open(package_path, encoding="utf-8").read())
                print("DECRYPT_OK")
                print(plain)
                payload = {
                    "source": package_path,
                    "secspider_loader": "self",
                    "token": "-",
                    "local_proxy_config": {},
                }
                extend = base64.b64encode(json.dumps(payload).encode("utf-8")).decode("ascii")
                print("RUNTIME_INIT", spider.init(extend))
                print("RUNTIME_NAME", spider.getName())
                print("RUNTIME_PLAYER", json.dumps(spider.playerContent("test", "video-id", []), ensure_ascii=False, sort_keys=True))
                """.formatted(escapePath(atvp), escapePath(packageFile));
    }

    private String escapePath(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String pyList(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append("\"").append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        return builder.append("]").toString();
    }

    private String replacePythonListAssignment(String source, String name, String replacementList) {
        return source.replaceFirst(
                "(?s)(?m)^    " + name + " = \\[(?:.*?^    \\]|[^\\n]*\\])",
                "    " + name + " = " + replacementList
        );
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        generator.initialize(NamedParameterSpec.ED25519);
        return generator.generateKeyPair();
    }

    private String pem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(bytes)
                + "\n-----END " + type + "-----";
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
