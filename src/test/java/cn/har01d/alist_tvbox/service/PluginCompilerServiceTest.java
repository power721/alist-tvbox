package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginCompilerServiceTest {
    private static final int PUBLIC_KEY_XOR = 23;
    private static final int MASTER_SECRET_XOR = 41;

    private final PluginCompilerService service = new PluginCompilerService();

    @Test
    void compileSecspiderShouldSignEncryptAndReturnKeyringChunks() throws Exception {
        KeyPair keyPair = generateKeyPair();

        String privateKey = pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        String publicKey = pem("PUBLIC KEY", keyPair.getPublic().getEncoded());
        String source = "from base.spider import Spider\n\nclass Spider(Spider):\n    pass\n";
        String masterSecret = "self-master-secret-for-test";

        PluginCompilerService.CompileResponse response = service.compileSecspider(
                new PluginCompilerService.CompileRequest(
                        "SelfPlugin",
                        3,
                        "",
                        "self-plugin-id",
                        "self-kid",
                        source,
                        privateKey,
                        publicKey,
                        masterSecret
                )
        );

        assertThat(response.packageText()).contains("//@format:secspider/1");
        assertThat(response.packageText()).contains("//@sign:ed25519");
        assertThat(response.packageText()).contains("//@kid:self-kid");
        assertThat(response.packageText()).doesNotContain(privateKey);
        assertThat(response.plainSha256()).isEqualTo(sha256Hex(source.getBytes(StandardCharsets.UTF_8)));
        assertThat(response.publicKeyChunks()).isNotEmpty();
        assertThat(response.masterSecretChunks()).isNotEmpty();

        Map<String, String> headers = parseHeaders(response.packageText());
        String payloadB64 = parsePayload(response.packageText());
        verifySignature(keyPair, headers, payloadB64);

        byte[] wrapKey = hkdf(
                masterSecret.getBytes(StandardCharsets.UTF_8),
                headers.get("kid").getBytes(StandardCharsets.UTF_8),
                "secspider:SelfPlugin:3:wrap-key".getBytes(StandardCharsets.UTF_8),
                32
        );
        byte[] wrapNonce = hkdf(
                masterSecret.getBytes(StandardCharsets.UTF_8),
                headers.get("kid").getBytes(StandardCharsets.UTF_8),
                "secspider:SelfPlugin:3:wrap-nonce".getBytes(StandardCharsets.UTF_8),
                12
        );
        byte[] contentKey = aesGcmDecrypt(wrapKey, wrapNonce, b64(stripPrefix(headers.get("ek"), "base64:")));
        byte[] plaintext = aesGcmDecrypt(
                contentKey,
                b64(stripPrefix(headers.get("nonce"), "base64:")),
                b64(payloadB64)
        );

        assertThat(new String(plaintext, StandardCharsets.UTF_8)).isEqualTo(source);
        assertThat(deobfuscate(response.publicKeyChunks(), PUBLIC_KEY_XOR)).contains("-----BEGIN PUBLIC KEY-----");
        assertThat(deobfuscate(response.masterSecretChunks(), MASTER_SECRET_XOR)).isEqualTo(masterSecret);
    }

    @Test
    void compileSecspiderShouldNormalizeBase64DerPublicKeyChunks() throws Exception {
        KeyPair keyPair = generateKeyPair();
        String publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        PluginCompilerService.CompileResponse response = service.compileSecspider(
                new PluginCompilerService.CompileRequest(
                        "SelfPlugin",
                        1,
                        "",
                        "self-plugin-id",
                        "self-kid",
                        "from base.spider import Spider\n\nclass Spider(Spider):\n    pass\n",
                        pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                        publicKeyBase64,
                        "self-master-secret-for-test"
                )
        );

        String restoredPublicKey = deobfuscate(response.publicKeyChunks(), PUBLIC_KEY_XOR);
        assertThat(restoredPublicKey).startsWith("-----BEGIN PUBLIC KEY-----");
        assertThat(restoredPublicKey).endsWith("-----END PUBLIC KEY-----");
    }

    @Test
    void compileSecspiderShouldRejectMismatchedPublicKey() throws Exception {
        KeyPair signingKey = generateKeyPair();
        KeyPair wrongKey = generateKeyPair();

        assertThatThrownBy(() -> service.compileSecspider(
                new PluginCompilerService.CompileRequest(
                        "SelfPlugin",
                        1,
                        "",
                        "self-plugin-id",
                        "self-kid",
                        "from base.spider import Spider\n\nclass Spider(Spider):\n    pass\n",
                        pem("PRIVATE KEY", signingKey.getPrivate().getEncoded()),
                        pem("PUBLIC KEY", wrongKey.getPublic().getEncoded()),
                        "self-master-secret-for-test"
                )
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("公钥与私钥不匹配");
    }

    @Test
    void compileSecspiderShouldRejectHeaderInjection() throws Exception {
        KeyPair keyPair = generateKeyPair();

        assertThatThrownBy(() -> service.compileSecspider(
                new PluginCompilerService.CompileRequest(
                        "SelfPlugin\n//@format:plain",
                        1,
                        "",
                        "self-plugin-id",
                        "self-kid",
                        "from base.spider import Spider\n\nclass Spider(Spider):\n    pass\n",
                        pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
                        pem("PUBLIC KEY", keyPair.getPublic().getEncoded()),
                        "self-master-secret-for-test"
                )
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不能包含换行");
    }

    @Test
    void compileSecspiderShouldAcceptRawSeedPrivateKey() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);

        PluginCompilerService.CompileResponse response = service.compileSecspider(
                new PluginCompilerService.CompileRequest(
                        "SeedPlugin",
                        1,
                        "",
                        "seed-plugin-id",
                        "seed-kid",
                        "from base.spider import Spider\n\nclass Spider(Spider):\n    pass\n",
                        HexFormat.of().formatHex(seed),
                        "",
                        "self-master-secret-for-test"
                )
        );

        assertThat(response.packageText()).contains("//@sig:base64:");
        assertThat(response.publicKeyChunks()).isEmpty();
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

    private Map<String, String> parseHeaders(String text) {
        Map<String, String> headers = new HashMap<>();
        for (String line : text.split("\\R")) {
            if (line.startsWith("//@")) {
                String body = line.substring(3);
                int index = body.indexOf(':');
                headers.put(body.substring(0, index), body.substring(index + 1));
            }
        }
        return headers;
    }

    private String parsePayload(String text) {
        for (String line : text.split("\\R")) {
            if (line.startsWith("payload.base64:")) {
                return stripPrefix(line, "payload.base64:");
            }
        }
        throw new IllegalArgumentException("missing payload");
    }

    private void verifySignature(KeyPair keyPair, Map<String, String> headers, String payloadB64) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initVerify(keyPair.getPublic());
        signature.update(buildSigningBytes(headers, payloadB64));
        assertThat(signature.verify(b64(stripPrefix(headers.get("sig"), "base64:")))).isTrue();
    }

    private byte[] buildSigningBytes(Map<String, String> headers, String payloadB64) {
        String text = String.join("\n",
                "//@name:" + headers.get("name"),
                "//@version:" + headers.get("version"),
                "//@remark:" + headers.get("remark"),
                "//@id:" + headers.get("id"),
                "//@format:" + headers.get("format"),
                "//@alg:" + headers.get("alg"),
                "//@wrap:" + headers.get("wrap"),
                "//@sign:" + headers.get("sign"),
                "//@kid:" + headers.get("kid"),
                "//@nonce:" + headers.get("nonce"),
                "//@ek:" + headers.get("ek"),
                "//@hash:" + headers.get("hash"),
                "payload.base64:" + payloadB64
        );
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] blob) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(blob);
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

    private byte[] b64(String text) {
        return Base64.getDecoder().decode(text);
    }

    private String stripPrefix(String text, String prefix) {
        return text != null && text.startsWith(prefix) ? text.substring(prefix.length()) : text;
    }

    private String deobfuscate(List<String> chunks, int xorKey) {
        List<byte[]> decoded = new ArrayList<>();
        for (int index = chunks.size() - 1; index >= 0; index--) {
            byte[] chunk = Base64.getDecoder().decode(chunks.get(index));
            for (int i = 0; i < chunk.length; i++) {
                chunk[i] = (byte) (chunk[i] ^ xorKey);
            }
            decoded.add(chunk);
        }
        int size = decoded.stream().mapToInt(item -> item.length).sum();
        byte[] raw = new byte[size];
        int offset = 0;
        for (byte[] chunk : decoded) {
            System.arraycopy(chunk, 0, raw, offset, chunk.length);
            offset += chunk.length;
        }
        return new String(raw, StandardCharsets.UTF_8);
    }
}
