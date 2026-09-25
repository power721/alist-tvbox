package cn.har01d.alist_tvbox.live.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 斗鱼纯函数单测:本地 MD5 签名(getEncryption 描述符口径)、Cookie did 一致化(pure_live issue #873)、
 * rtmp_live 完整 URL 保护。网络链路(getEncryption/getH5PlayV1)不做离线桩,以实测探针为准。
 */
class DouyuServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String DESCRIPTOR = """
            {"key":"cXygpAO0ZFgTsOwHs9b6678979","rand_str":"iOlVNxqMdbKSTyxq",
             "enc_time":1,"expire_at":1790000600,"is_special":0,"enc_data":"ED+TA/=="}""";

    @Test
    void buildSignedFormMatchesDouyuAlgorithm() {
        // 固定向量:secret=md5(rand_str+key),auth=md5(secret+key+roomId+tt),由 python 独立实现预计算
        String form = DouyuService.buildSignedForm("9999", objectMapper.valueToTree(
                        java.util.Map.of("key", "cXygpAO0ZFgTsOwHs9b6678979", "rand_str", "iOlVNxqMdbKSTyxq",
                                "enc_time", 1, "is_special", 0, "enc_data", "ED1")),
                "abcdef0123456789abcdef0123456789", 1790000000L);
        assertTrue(form.contains("auth=36e9cc5eb67637dbe932de771c481149"), form);
        assertTrue(form.contains("tt=1790000000"), form);
        assertTrue(form.contains("did=abcdef0123456789abcdef0123456789"), form);
    }

    @Test
    void buildSignedFormIteratesSecretAndDropsSaltForSpecial() {
        // enc_time=2 迭代两轮,is_special=1 时盐为空(向量由 python 独立预计算)
        String form = DouyuService.buildSignedForm("123", objectMapper.valueToTree(
                        java.util.Map.of("key", "cXygpAO0ZFgTsOwHs9b6678979", "rand_str", "iOlVNxqMdbKSTyxq",
                                "enc_time", 2, "is_special", 1, "enc_data", "ED2")),
                "abcdef0123456789abcdef0123456789", 1789999999L);
        assertTrue(form.contains("auth=0473b75740aa288591e58310b337bf15"), form);
    }

    @Test
    void buildSignedFormUrlEncodesEncData() {
        String form = DouyuService.buildSignedForm("9999", objectMapper.valueToTree(
                        java.util.Map.of("key", "k", "rand_str", "r", "enc_time", 1, "is_special", 0,
                                "enc_data", "a+b/c==")),
                "did", 1790000000L);
        String encData = form.substring(form.indexOf("enc_data=") + 9, form.indexOf("&tt="));
        assertEquals("a+b/c==", URLDecoder.decode(encData, StandardCharsets.UTF_8));
        assertFalse(encData.contains("+"));
    }

    @Test
    void encryptionKeyUsableChecksExpiryAndFields() throws Exception {
        var key = objectMapper.readTree(DESCRIPTOR);
        // expire_at=1790000600,now=1790000000 恰好余 600s>30s 边际
        assertTrue(DouyuService.isEncryptionKeyUsable(key, 1790000000L));
        // 过期前 30s 内不可用
        assertFalse(DouyuService.isEncryptionKeyUsable(key, 1790000580L));
        // 关键字段缺失/enc_time 越界
        assertFalse(DouyuService.isEncryptionKeyUsable(objectMapper.readTree(
                "{\"key\":\"k\",\"rand_str\":\"r\",\"enc_time\":1,\"expire_at\":1790000600}"), 1790000000L));
        assertFalse(DouyuService.isEncryptionKeyUsable(objectMapper.readTree(
                "{\"key\":\"k\",\"rand_str\":\"r\",\"enc_time\":17,\"expire_at\":1790000600,\"enc_data\":\"e\"}"), 1790000000L));
        // 空节点(接口返回无 data)不可用
        assertFalse(DouyuService.isEncryptionKeyUsable(objectMapper.missingNode(), 1790000000L));
    }

    @Test
    void cookieHeaderReplacesDidFieldsAndKeepsSession() {
        String user = "Cookie: dy_did=OLD_DID; acf_did=OLD_DID; acf_auth=token123; dy_auth=login%20x; jdafnid";
        String header = DouyuService.buildCookieHeader("NEW_DID", user);
        // 粘贴值里的 dy_did/acf_did 被签名 DID 取代,防表单 did 与请求头冲突;登录字段原样保留,无 = 的碎片丢弃
        assertEquals("dy_did=NEW_DID; acf_did=NEW_DID; acf_auth=token123; dy_auth=login%20x", header);
    }

    @Test
    void cookieHeaderAnonymousHasOnlyDidPair() {
        assertEquals("dy_did=D; acf_did=D", DouyuService.buildCookieHeader("D", ""));
        assertEquals("dy_did=D; acf_did=D", DouyuService.buildCookieHeader("D", null));
    }

    @Test
    void combinePlayUrlPrefersCompleteUrlAndNormalizesSlashes() {
        // rtmp_live 已是完整签名 URL:直接用,拼 rtmp_url 会产出不可播的双 URL
        assertEquals("https://cdn.douyu.com/live/9999.flv?wsAuth=abc",
                DouyuService.combinePlayUrl("https://other.douyucdn.cn/live", "https://cdn.douyu.com/live/9999.flv?wsAuth=abc"));
        // 常规形态:base/path 拼接,尾斜杠归一,HTML 实体还原
        assertEquals("https://hw.douyucdn.cn/live/9999.flv?wsAuth=a&b",
                DouyuService.combinePlayUrl("https://hw.douyucdn.cn/live/", "9999.flv?wsAuth=a&amp;b"));
    }
}
