package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class SecspiderKeyService {
    private static final int PUBLIC_KEY_XOR = 23;
    private static final int MASTER_SECRET_XOR = 41;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ObjectMapper objectMapper;

    public SecspiderKeyService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initialize() {
        try {
            ensureKeyMaterial();
        } catch (Exception e) {
            log.warn("failed to initialize secspider key material", e);
        }
    }

    public synchronized KeyStatus ensureKeyMaterial() {
        if (!hasKeyMaterial()) {
            return generateKeyMaterial(false);
        }
        return status();
    }

    public synchronized KeyStatus status() {
        try {
            ensureKeyringFile();
            String publicKey = Files.readString(publicKeyPath(), StandardCharsets.UTF_8);
            String masterSecret = Files.readString(masterSecretPath(), StandardCharsets.UTF_8).trim();
            return new KeyStatus(
                    true,
                    privateKeyPath().toString(),
                    publicKeyPath().toString(),
                    masterSecretPath().toString(),
                    keyringPath().toString(),
                    publicKey,
                    obfuscateText(publicKey, PUBLIC_KEY_XOR, 16),
                    obfuscateText(masterSecret, MASTER_SECRET_XOR, 16)
            );
        } catch (IOException e) {
            throw new BadRequestException("secspider 密钥读取失败: " + e.getMessage(), e);
        }
    }

    public synchronized KeyStatus generateKeyMaterial(boolean overwrite) {
        if (!overwrite && hasKeyMaterial()) {
            return status();
        }
        try {
            Files.createDirectories(baseDir());
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            generator.initialize(NamedParameterSpec.ED25519);
            KeyPair keyPair = generator.generateKeyPair();

            String privateKey = toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
            String publicKey = toPem("PUBLIC KEY", keyPair.getPublic().getEncoded());
            String masterSecret = "self-master-" + HexFormat.of().formatHex(randomBytes(32));

            Files.writeString(privateKeyPath(), privateKey, StandardCharsets.UTF_8);
            Files.writeString(publicKeyPath(), publicKey, StandardCharsets.UTF_8);
            Files.writeString(masterSecretPath(), masterSecret + "\n", StandardCharsets.UTF_8);
            writeKeyringFile(publicKey, masterSecret);
            restrictOwnerOnly(privateKeyPath());
            restrictOwnerOnly(masterSecretPath());
            log.info("generated secspider key material at {}", baseDir());
            return status();
        } catch (Exception e) {
            throw new BadRequestException("secspider 密钥生成失败: " + e.getMessage(), e);
        }
    }

    public CompilerKeyMaterial compilerKeyMaterial() {
        ensureKeyMaterial();
        try {
            return new CompilerKeyMaterial(
                    Files.readString(privateKeyPath(), StandardCharsets.UTF_8),
                    Files.readString(publicKeyPath(), StandardCharsets.UTF_8),
                    Files.readString(masterSecretPath(), StandardCharsets.UTF_8).trim()
            );
        } catch (IOException e) {
            throw new BadRequestException("secspider 密钥读取失败: " + e.getMessage(), e);
        }
    }

    private boolean hasKeyMaterial() {
        return Files.isRegularFile(privateKeyPath())
                && Files.isRegularFile(publicKeyPath())
                && Files.isRegularFile(masterSecretPath());
    }

    private void ensureKeyringFile() throws IOException {
        if (!hasKeyMaterial()) {
            return;
        }
        String publicKey = Files.readString(publicKeyPath(), StandardCharsets.UTF_8);
        String masterSecret = Files.readString(masterSecretPath(), StandardCharsets.UTF_8).trim();
        writeKeyringFile(publicKey, masterSecret);
    }

    private void writeKeyringFile(String publicKey, String masterSecret) throws IOException {
        Files.createDirectories(baseDir());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(keyringPath().toFile(), Map.of(
                "format", "atvp-secspider-keyring/1",
                "updatedAt", OffsetDateTime.now().toString(),
                "publicKeyChunks", obfuscateText(publicKey, PUBLIC_KEY_XOR, 16),
                "masterSecretChunks", obfuscateText(masterSecret, MASTER_SECRET_XOR, 16)
        ));
    }

    private Path baseDir() {
        return Utils.getDataPath("secspider");
    }

    private Path privateKeyPath() {
        return baseDir().resolve("self-ed25519-private.pem");
    }

    private Path publicKeyPath() {
        return baseDir().resolve("self-ed25519-public.pem");
    }

    private Path masterSecretPath() {
        return baseDir().resolve("self-master-secret.txt");
    }

    private Path keyringPath() {
        return baseDir().resolve("atvp-keyring.json");
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    private String toPem(String type, byte[] bytes) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(bytes)
                + "\n-----END " + type + "-----\n";
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

    private void restrictOwnerOnly(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            ));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and some filesystems do not support POSIX permissions.
        }
    }

    public record CompilerKeyMaterial(String privateKey, String publicKey, String masterSecret) {
    }

    public record KeyStatus(
            boolean generated,
            String privateKeyPath,
            String publicKeyPath,
            String masterSecretPath,
            String keyringPath,
            String publicKey,
            List<String> publicKeyChunks,
            List<String> masterSecretChunks
    ) {
    }
}
