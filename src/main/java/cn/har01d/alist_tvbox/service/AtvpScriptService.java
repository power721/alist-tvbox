package cn.har01d.alist_tvbox.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AtvpScriptService {
    private static final Pattern SELF_PUBLIC_KEY_CHUNKS = Pattern.compile(
            "(?s)(?m)^    _self_public_key_chunks = \\[(?:.*?^    \\]|[^\\n]*\\])"
    );
    private static final Pattern SELF_MASTER_SECRET_CHUNKS = Pattern.compile(
            "(?s)(?m)^    _self_master_secret_chunks = \\[(?:.*?^    \\]|[^\\n]*\\])"
    );

    private final SecspiderKeyService secspiderKeyService;
    private final ObjectMapper objectMapper;

    public AtvpScriptService(SecspiderKeyService secspiderKeyService, ObjectMapper objectMapper) {
        this.secspiderKeyService = secspiderKeyService;
        this.objectMapper = objectMapper;
    }

    public String render() throws IOException {
        String script = readBundledAtvp();
        SecspiderKeyService.KeyStatus keyStatus = secspiderKeyService.ensureKeyMaterial();
        script = replaceList(script, SELF_PUBLIC_KEY_CHUNKS, "_self_public_key_chunks", keyStatus.publicKeyChunks());
        script = replaceList(script, SELF_MASTER_SECRET_CHUNKS, "_self_master_secret_chunks", keyStatus.masterSecretChunks());
        return script;
    }

    public String version() {
        SecspiderKeyService.KeyStatus keyStatus = secspiderKeyService.ensureKeyMaterial();
        String material = String.join("|", keyStatus.publicKeyChunks())
                + "\n"
                + String.join("|", keyStatus.masterSecretChunks());
        return DigestUtils.sha256Hex(material).substring(0, 12);
    }

    private String readBundledAtvp() throws IOException {
        ClassPathResource resource = new ClassPathResource("static/Atvp.py");
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String replaceList(String source, Pattern pattern, String name, List<String> values) throws IOException {
        String replacement = "    " + name + " = " + objectMapper.writeValueAsString(values);
        String updated = pattern.matcher(source).replaceFirst(Matcher.quoteReplacement(replacement));
        if (updated.equals(source)) {
            throw new IOException("Atvp.py missing assignment for " + name);
        }
        return updated;
    }
}
