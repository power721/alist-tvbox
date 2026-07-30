package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class PluginCompilerService {
    private static final int PUBLIC_KEY_XOR = 23;
    private static final int MASTER_SECRET_XOR = 41;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern SPIDER_IMPORT_PATTERN = Pattern.compile("(?m)^\\s*from\\s+base\\.spider\\s+import\\s+Spider\\s*$");
    private static final Pattern SPIDER_CLASS_PATTERN = Pattern.compile("(?m)^\\s*class\\s+Spider\\s*\\(\\s*Spider\\s*\\)\\s*:\\s*$");
    private static final Pattern DETAIL_CONTENT_PATTERN = Pattern.compile("(?m)^\\s*def\\s+detailContent\\s*\\(");
    private static final Pattern PLAYER_CONTENT_PATTERN = Pattern.compile("(?m)^\\s*def\\s+playerContent\\s*\\(");
    private static final Pattern VOD_PIC_PATTERN = Pattern.compile("\\bvod_pic\\b");
    private static final Pattern VOD_PLAY_FROM_PATTERN = Pattern.compile("\\bvod_play_from\\b");
    private static final Pattern VOD_PLAY_URL_PATTERN = Pattern.compile("\\bvod_play_url\\b");
    private static final Pattern MAGNET_FLOW_PATTERN = Pattern.compile("(?i)magnet:|btih");
    private static final Pattern OUTER_HEADER_PATTERN = Pattern.compile("(?m)^\\s*//@");

    public CompileResponse compileSecspider(CompileRequest request) {
        validateRequest(request);

        try {
            String name = StringUtils.trim(request.name());
            String version = String.valueOf(request.version());
            String remark = StringUtils.defaultString(request.remark());
            String id = StringUtils.trimToEmpty(request.id());
            String kid = StringUtils.defaultIfBlank(StringUtils.trim(request.kid()), "self");
            byte[] sourceBytes = request.source().getBytes(StandardCharsets.UTF_8);
            byte[] masterSecret = request.masterSecret().getBytes(StandardCharsets.UTF_8);
            PrivateKey privateKey = parseEd25519PrivateKey(request.privateKey());
            PublicKey publicKey = StringUtils.isBlank(request.publicKey()) ? null : parseEd25519PublicKey(request.publicKey());

            byte[] contentKey = randomBytes(32);
            byte[] payloadNonce = randomBytes(12);
            byte[] payloadBlob = aesGcmEncrypt(contentKey, payloadNonce, sourceBytes);

            byte[] wrapKey = hkdf(masterSecret, kid.getBytes(StandardCharsets.UTF_8),
                    ("secspider:" + name + ":" + version + ":wrap-key").getBytes(StandardCharsets.UTF_8), 32);
            byte[] wrapNonce = hkdf(masterSecret, kid.getBytes(StandardCharsets.UTF_8),
                    ("secspider:" + name + ":" + version + ":wrap-nonce").getBytes(StandardCharsets.UTF_8), 12);
            byte[] wrappedKey = aesGcmEncrypt(wrapKey, wrapNonce, contentKey);

            String payloadB64 = Base64.getEncoder().encodeToString(payloadBlob);
            String sourceHash = sha256Hex(sourceBytes);
            SecspiderHeaders headers = new SecspiderHeaders(
                    name,
                    version,
                    remark,
                    id,
                    "secspider/1",
                    "aes-256-gcm",
                    "hkdf-aes-keywrap",
                    "ed25519",
                    kid,
                    "base64:" + Base64.getEncoder().encodeToString(payloadNonce),
                    "base64:" + Base64.getEncoder().encodeToString(wrappedKey),
                    "sha256:" + sourceHash
            );

            byte[] signingBytes = buildSigningBytes(headers, payloadB64);
            byte[] signature = signEd25519(privateKey, signingBytes);
            if (publicKey != null && !verifyEd25519(publicKey, signingBytes, signature)) {
                throw new BadRequestException("Ed25519 公钥与私钥不匹配");
            }
            String packageText = buildPackageText(headers, "base64:" + Base64.getEncoder().encodeToString(signature), payloadB64);

            List<String> publicKeyChunks = publicKey == null
                    ? List.of()
                    : obfuscateText(toPem("PUBLIC KEY", publicKey.getEncoded()), PUBLIC_KEY_XOR, 16);
            List<String> masterSecretChunks = obfuscateText(request.masterSecret(), MASTER_SECRET_XOR, 16);

            return new CompileResponse(
                    packageText,
                    sourceHash,
                    packageText.getBytes(StandardCharsets.UTF_8).length,
                    "secspider/1",
                    "aes-256-gcm",
                    "hkdf-aes-keywrap",
                    "ed25519",
                    kid,
                    publicKeyChunks,
                    masterSecretChunks,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("插件编译失败: " + e.getMessage(), e);
        }
    }

    public CompatibilityCheckResponse checkMagnetSpiderCompatibility(CompatibilityCheckRequest request) {
        validateCompatibilityRequest(request);

        try {
            String name = StringUtils.trim(request.name());
            String id = StringUtils.trimToEmpty(request.id());
            String pluginId = StringUtils.defaultIfBlank(id, derivePluginId(name));
            String source = StringUtils.defaultString(request.source());
            List<CompatibilityCheckItem> items = new ArrayList<>();

            items.add(checkItem("outer_headers", "内层明文包头", !OUTER_HEADER_PATTERN.matcher(source).find(),
                    "内层明文未混入 //@ 包头",
                    "如果这是 secspider 外层包体，请把外层头部移到包体生成器里，内层只保留 Python 源码"));
            items.add(checkItem("base_spider_import", "Spider 导入", SPIDER_IMPORT_PATTERN.matcher(source).find(),
                    "已包含 from base.spider import Spider",
                    "保留 AList-TVBox 的 Spider 基类导入"));
            items.add(checkItem("spider_class", "Spider 类定义", SPIDER_CLASS_PATTERN.matcher(source).find(),
                    "已包含 class Spider(Spider):",
                    "保留 Spider(Spider) 继承声明"));
            items.add(checkItem("detail_content", "详情入口", DETAIL_CONTENT_PATTERN.matcher(source).find(),
                    "已包含 detailContent",
                    "确保详情页能够返回磁链卡片与封面图"));
            items.add(checkItem("player_content", "播放入口", PLAYER_CONTENT_PATTERN.matcher(source).find(),
                    "已包含 playerContent",
                    "确保播放时能进入插件内部解析流程"));
            items.add(checkItem("vod_pic", "封面字段", VOD_PIC_PATTERN.matcher(source).find(),
                    "已包含 vod_pic",
                    "详情和选片卡片应保留封面图"));
            items.add(checkItem("vod_play_from", "播放分组", VOD_PLAY_FROM_PATTERN.matcher(source).find(),
                    "已包含 vod_play_from",
                    "保留播放分组，避免详情页被压扁成纯文本"));
            items.add(checkItem("vod_play_url", "播放列表", VOD_PLAY_URL_PATTERN.matcher(source).find(),
                    "已包含 vod_play_url",
                    "保留播放列表，确保卡片能绑定到具体磁链"));
            items.add(checkItem("magnet_flow", "磁力链标记", MAGNET_FLOW_PATTERN.matcher(source).find(),
                    "已检测到 magnet / btih 相关标记",
                    "磁力爬虫门禁需要能看见磁力链处理路径"));

            long failCount = items.stream().filter(item -> "FAIL".equals(item.status())).count();
            long passCount = items.size() - failCount;
            boolean passed = failCount == 0;
            String summary = passed
                    ? "磁力爬虫门禁通过"
                    : "磁力爬虫门禁未通过：" + failCount + " 项不合规";

            return new CompatibilityCheckResponse(
                    "磁力爬虫门禁",
                    name,
                    pluginId,
                    request.version(),
                    sha256Hex(source.getBytes(StandardCharsets.UTF_8)),
                    passed,
                    (int) passCount,
                    (int) failCount,
                    summary,
                    items,
                    buildAiRepairExportText(name, pluginId, request.version(), source, source.getBytes(StandardCharsets.UTF_8), passed, summary, items)
            );
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("兼容性校验失败: " + e.getMessage(), e);
        }
    }

    private void validateRequest(CompileRequest request) {
        if (request == null) {
            throw new BadRequestException("请求不能为空");
        }
        if (StringUtils.isBlank(request.name())) {
            throw new BadRequestException("插件名称不能为空");
        }
        validateHeaderValue("插件名称", request.name());
        if (request.version() == null || request.version() < 1) {
            throw new BadRequestException("插件版本必须大于 0");
        }
        validateHeaderValue("remark", request.remark());
        validateHeaderValue("插件 ID", request.id());
        validateHeaderValue("kid", request.kid());
        if (StringUtils.isBlank(request.source())) {
            throw new BadRequestException("插件明文不能为空");
        }
        if (StringUtils.isBlank(request.privateKey())) {
            throw new BadRequestException("Ed25519 私钥不能为空");
        }
        if (StringUtils.isBlank(request.masterSecret())) {
            throw new BadRequestException("master secret 不能为空");
        }
    }

    private void validateCompatibilityRequest(CompatibilityCheckRequest request) {
        if (request == null) {
            throw new BadRequestException("请求不能为空");
        }
        if (StringUtils.isBlank(request.name())) {
            throw new BadRequestException("插件名称不能为空");
        }
        validateHeaderValue("插件名称", request.name());
        if (request.version() == null || request.version() < 1) {
            throw new BadRequestException("插件版本必须大于 0");
        }
        validateHeaderValue("remark", request.remark());
        validateHeaderValue("插件 ID", request.id());
        if (StringUtils.isBlank(request.source())) {
            throw new BadRequestException("插件明文不能为空");
        }
    }

    private void validateHeaderValue(String label, String value) {
        if (value != null && (value.contains("\n") || value.contains("\r"))) {
            throw new BadRequestException(label + "不能包含换行");
        }
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    private PrivateKey parseEd25519PrivateKey(String input) throws Exception {
        byte[] keyBytes = decodeKeyMaterial(input);
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        try {
            return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception ignored) {
            if (keyBytes.length != 32) {
                throw new BadRequestException("Ed25519 私钥需为 PKCS8 PEM/base64，或 32 字节 raw seed");
            }
            return keyFactory.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, keyBytes));
        }
    }

    private PublicKey parseEd25519PublicKey(String input) throws Exception {
        byte[] keyBytes = decodeKeyMaterial(input);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new BadRequestException("Ed25519 公钥需为 X509 PEM/base64", e);
        }
    }

    private byte[] decodeKeyMaterial(String input) {
        String value = StringUtils.trimToEmpty(input);
        if (value.contains("-----BEGIN")) {
            value = value.replaceAll("-----BEGIN [^-]+-----", "")
                    .replaceAll("-----END [^-]+-----", "")
                    .replaceAll("\\s+", "");
            return Base64.getDecoder().decode(value);
        }
        String compact = value.replaceAll("\\s+", "");
        if (compact.matches("(?i)^[0-9a-f]{64}$")) {
            return HexFormat.of().parseHex(compact);
        }
        return Base64.getDecoder().decode(compact);
    }

    private String toPem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(bytes)
                + "\n-----END " + type + "-----";
    }

    private byte[] aesGcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(plaintext);
    }

    private byte[] hkdf(byte[] master, byte[] salt, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(master);

        byte[] result = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        int counter = 1;
        while (offset < length) {
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(previous);
            mac.update(info);
            mac.update((byte) counter);
            previous = mac.doFinal();
            int copy = Math.min(previous.length, length - offset);
            System.arraycopy(previous, 0, result, offset, copy);
            offset += copy;
            counter++;
        }
        return result;
    }

    private String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private byte[] signEd25519(PrivateKey privateKey, byte[] data) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(data);
        return signature.sign();
    }

    private boolean verifyEd25519(PublicKey publicKey, byte[] data, byte[] signatureBytes) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initVerify(publicKey);
        signature.update(data);
        return signature.verify(signatureBytes);
    }

    private String derivePluginId(String name) {
        String normalized = StringUtils.defaultString(name)
                .trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9_.-]+", "_")
                .replaceAll("^_+|_+$", "");
        if (StringUtils.isNotBlank(normalized)) {
            return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
        }
        return "plugin_" + DigestUtils.sha256Hex(StringUtils.defaultString(name)).substring(0, 12);
    }

    private CompatibilityCheckItem checkItem(String code, String title, boolean passed, String message, String suggestion) {
        return new CompatibilityCheckItem(code, title, passed ? "PASS" : "FAIL", message, suggestion);
    }

    private String buildAiRepairExportText(String pluginName,
                                           String pluginId,
                                           Integer version,
                                           String source,
                                           byte[] sourceBytes,
                                           boolean passed,
                                           String summary,
                                           List<CompatibilityCheckItem> items) throws Exception {
        List<String> repairSuggestions = items.stream()
                .filter(item -> "FAIL".equals(item.status()))
                .map(CompatibilityCheckItem::suggestion)
                .toList();
        CompatibilityExportPayload payload = new CompatibilityExportPayload(
                "AI修复导出",
                "把错误项需要修改的地方用 AI 更容易理解的方式导出成文本，提交给 AI 修复后再次检查。",
                "磁力爬虫门禁",
                pluginName,
                pluginId,
                version,
                sha256Hex(sourceBytes),
                passed,
                summary,
                repairSuggestions,
                items,
                source
        );
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
    }

    private byte[] buildSigningBytes(SecspiderHeaders headers, String payloadB64) {
        return String.join("\n", signingLines(headers, payloadB64)).getBytes(StandardCharsets.UTF_8);
    }

    private List<String> signingLines(SecspiderHeaders headers, String payloadB64) {
        List<String> lines = new ArrayList<>();
        lines.add("//@name:" + headers.name());
        lines.add("//@version:" + headers.version());
        lines.add("//@remark:" + headers.remark());
        if (StringUtils.isNotBlank(headers.id())) {
            lines.add("//@id:" + headers.id());
        }
        lines.add("//@format:" + headers.format());
        lines.add("//@alg:" + headers.alg());
        lines.add("//@wrap:" + headers.wrap());
        lines.add("//@sign:" + headers.sign());
        lines.add("//@kid:" + headers.kid());
        lines.add("//@nonce:" + headers.nonce());
        lines.add("//@ek:" + headers.ek());
        lines.add("//@hash:" + headers.hash());
        lines.add("payload.base64:" + payloadB64);
        return lines;
    }

    private String buildPackageText(SecspiderHeaders headers, String signature, String payloadB64) {
        List<String> lines = new ArrayList<>();
        lines.add("//@name:" + headers.name());
        lines.add("//@version:" + headers.version());
        lines.add("//@remark:" + headers.remark());
        if (StringUtils.isNotBlank(headers.id())) {
            lines.add("//@id:" + headers.id());
        }
        lines.add("//@format:" + headers.format());
        lines.add("//@alg:" + headers.alg());
        lines.add("//@wrap:" + headers.wrap());
        lines.add("//@sign:" + headers.sign());
        lines.add("//@kid:" + headers.kid());
        lines.add("//@nonce:" + headers.nonce());
        lines.add("//@ek:" + headers.ek());
        lines.add("//@hash:" + headers.hash());
        lines.add("//@sig:" + signature);
        lines.add("");
        lines.add("payload.base64:" + payloadB64);
        return String.join("\n", lines) + "\n";
    }

    private List<String> obfuscateText(String text, int xorKey, int chunkSize) {
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        List<String> chunks = new ArrayList<>();
        for (int index = 0; index < raw.length; index += chunkSize) {
            int end = Math.min(index + chunkSize, raw.length);
            byte[] chunk = ByteBuffer.allocate(end - index).put(raw, index, end - index).array();
            for (int i = 0; i < chunk.length; i++) {
                chunk[i] = (byte) (chunk[i] ^ xorKey);
            }
            chunks.add(Base64.getEncoder().encodeToString(chunk));
        }
        List<String> reversed = new ArrayList<>();
        for (int index = chunks.size() - 1; index >= 0; index--) {
            reversed.add(chunks.get(index));
        }
        return reversed;
    }

    private record SecspiderHeaders(
            String name,
            String version,
            String remark,
            String id,
            String format,
            String alg,
            String wrap,
            String sign,
            String kid,
            String nonce,
            String ek,
            String hash
    ) {
    }

    public record CompileRequest(
            String name,
            Integer version,
            String remark,
            String id,
            String kid,
            String source,
            String privateKey,
            String publicKey,
            String masterSecret,
            Boolean useManagedKey,
            Boolean autoImport
    ) {
        public CompileRequest(
                String name,
                Integer version,
                String remark,
                String id,
                String kid,
                String source,
                String privateKey,
                String publicKey,
                String masterSecret
        ) {
            this(name, version, remark, id, kid, source, privateKey, publicKey, masterSecret, null, null);
        }
    }

    public record CompatibilityCheckRequest(
            String name,
            Integer version,
            String remark,
            String id,
            String source
    ) {
    }

    public record CompatibilityCheckItem(
            String code,
            String title,
            String status,
            String message,
            String suggestion
    ) {
    }

    public record CompatibilityCheckResponse(
            String gateName,
            String pluginName,
            String pluginId,
            Integer version,
            String sourceSha256,
            boolean passed,
            int passCount,
            int failCount,
            String summary,
            List<CompatibilityCheckItem> items,
            String aiRepairExportText
    ) {
    }

    public record CompatibilityExportPayload(
            String exportType,
            String description,
            String gateName,
            String pluginName,
            String pluginId,
            Integer version,
            String sourceSha256,
            boolean passed,
            String summary,
            List<String> repairSuggestions,
            List<CompatibilityCheckItem> items,
            String source
    ) {
    }

    public record CompileResponse(
            String packageText,
            String plainSha256,
            int packageSize,
            String format,
            String alg,
            String wrap,
            String sign,
            String kid,
            List<String> publicKeyChunks,
            List<String> masterSecretChunks,
            Integer importedPluginId,
            String importedPluginName,
            String pluginUrl,
            String repositoryUrl,
            String localPath
    ) {
        public CompileResponse withImportedPlugin(Integer importedPluginId,
                                                  String importedPluginName,
                                                  String pluginUrl,
                                                  String repositoryUrl,
                                                  String localPath) {
            return new CompileResponse(
                    packageText,
                    plainSha256,
                    packageSize,
                    format,
                    alg,
                    wrap,
                    sign,
                    kid,
                    publicKeyChunks,
                    masterSecretChunks,
                    importedPluginId,
                    importedPluginName,
                    pluginUrl,
                    repositoryUrl,
                    localPath
            );
        }
    }
}
