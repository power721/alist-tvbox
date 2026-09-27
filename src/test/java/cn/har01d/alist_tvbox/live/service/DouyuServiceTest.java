package cn.har01d.alist_tvbox.live.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void cookieHeaderDropsLongTermKeyAndAttributeFragments() {
        // LTP0 是 passport 续期密钥,进播放请求头会被边缘风控 403(pure_live c29a3b85 形态 3);
        // Set-Cookie 属性碎片(Path=/ 等)不是字段,不得混进请求头
        String user = "dy_auth=web_login; LTP0=longterm; acf_stk=stk1; Path=/live; Expires=Wed, 21 Oct 2026";
        String header = DouyuService.buildCookieHeader("D", user);
        assertEquals("dy_did=D; acf_did=D; dy_auth=web_login; acf_stk=stk1", header);
    }

    @Test
    void sessionTokenPrefersH5JwtThenAuthThenWebToken() {
        // H5 双形态优先,网页版 dy_auth 也算登录(pure_live 7d5187ca:只认 H5 会把网页登录判成游客)
        assertEquals("jwt1", DouyuService.sessionToken("acf_jwt_token=jwt1; acf_auth=auth1; dy_auth=web1"));
        assertEquals("auth1", DouyuService.sessionToken("acf_auth=auth1; dy_auth=web1"));
        assertEquals("web1", DouyuService.sessionToken("dy_did=d; dy_auth=web1"));
        assertNull(DouyuService.sessionToken("dy_did=d; acf_stk=x"));
        assertNull(DouyuService.sessionToken(""));
        assertNull(DouyuService.sessionToken(null));
    }

    @Test
    void jwtExpiryReadsExpAndToleratesNonJwt() throws Exception {
        assertEquals(1790000000L, DouyuService.jwtExpirySeconds(jwt(1790000000L)));
        // 非 JWT(网页版不透明 token)/畸形 payload → null,不炸链路
        assertNull(DouyuService.jwtExpirySeconds("opaque-web-token"));
        assertNull(DouyuService.jwtExpirySeconds("a.####.c"));
        assertNull(DouyuService.jwtExpirySeconds(null));
    }

    /** 造一个 payload 带 exp 的三段式 JWT(base64url,无填充,还原斗鱼 acf_jwt_token 形态)。 */
    private static String jwt(long exp) throws Exception {
        String payload = "{\"exp\":" + exp + "}";
        return "h." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".s";
    }

    @Test
    void sessionExpiryUsesJwtThenSavedAtPlusSevenDays() throws Exception {
        // H5 JWT:exp 直读,与保存时间无关
        assertEquals(1790000000L, DouyuService.sessionExpirySeconds("acf_jwt_token=" + jwt(1790000000L), 100L));
        // 网页版不透明 token:保存时间 + 7 天折算
        assertEquals(100L + 7 * 24 * 3600, DouyuService.sessionExpirySeconds("dy_auth=web", 100L));
        // 折算无从起算 = 未知,不猜(pure_live:猜过期会否掉仍可用的登录)
        assertNull(DouyuService.sessionExpirySeconds("dy_auth=web", null));
        assertNull(DouyuService.sessionExpirySeconds("acf_stk=x", 100L));
    }

    @Test
    void shouldRefreshSessionEntersWindowOneDayBeforeExpiry() throws Exception {
        String jwtCookie = "acf_jwt_token=" + jwt(1790000000L);
        long expiry = 1790000000L;
        // 距到期 >1 天:不续
        assertFalse(DouyuService.shouldRefreshSession(jwtCookie, null, expiry - 24 * 3600 - 1));
        // 进入到期前 1 天窗口:续
        assertTrue(DouyuService.shouldRefreshSession(jwtCookie, null, expiry - 24 * 3600));
        // 到期时间未知(网页 Cookie 无保存时间):不续,交给播放失败强续路径
        assertFalse(DouyuService.shouldRefreshSession("dy_auth=web", null, 1790000000L));
        // 无 token(仅 passport 凭证串):恒应续,借此续出完整会话(pure_live 同款)
        assertTrue(DouyuService.shouldRefreshSession("LTP0=lt; dy_did=d", null, 1790000000L));
    }

    @Test
    void effectiveDeviceIdPrefersLoginDeviceFromCookie() {
        // 账号 Cookie 自带 dy_did(登录所属设备)时以它为准:用另一个 DID 签名=有效登录被按游客作答
        assertEquals("LOGIN_DID", DouyuService.effectiveDeviceId("dy_did=LOGIN_DID; dy_auth=x", "PROCESS_DID"));
        assertEquals("PROCESS_DID", DouyuService.effectiveDeviceId("dy_auth=x", "PROCESS_DID"));
        assertEquals("PROCESS_DID", DouyuService.effectiveDeviceId("", "PROCESS_DID"));
        assertEquals("PROCESS_DID", DouyuService.effectiveDeviceId(null, "PROCESS_DID"));
    }

    @Test
    void mergeSetCookieKeepsUnmentionedFieldsAndDropsAttributes() {
        String old = "dy_auth=login; LTP0=lt; dy_did=d1";
        // 未提及的字段保留(丢 LTP0 = 丢下次续期能力);属性不当字段;空值=服务端清空须删除
        String merged = DouyuService.mergeSetCookieLines(old, java.util.List.of(
                "dy_auth=newlogin; Path=/; Domain=.douyu.com; Expires=Wed, 21 Oct 2026 07:28:00 GMT; SameSite=Lax",
                "acf_stk=stk2; HttpOnly",
                "acf_ccn=; Path=/"));
        assertTrue(merged.contains("dy_auth=newlogin"));
        assertTrue(merged.contains("LTP0=lt"));
        assertTrue(merged.contains("dy_did=d1"));
        assertTrue(merged.contains("acf_stk=stk2"));
        assertFalse(merged.contains("acf_ccn"));
        assertFalse(merged.toLowerCase().contains("path"));
        assertFalse(merged.contains("Expires"));
    }

    @Test
    void mergeSetCookieEmptyValueRemovesField() {
        String merged = DouyuService.mergeSetCookieLines("dy_auth=login; acf_auth=a",
                java.util.List.of("acf_auth=; Path=/"));
        assertTrue(merged.contains("dy_auth=login"));
        assertFalse(merged.contains("acf_auth"));
    }

    @Test
    void normalizePastedCookieKeepsLoginWhenPasteIsPassportOnly() {
        String old = "dy_did=LOGIN_DID; dy_auth=login; acf_stk=old";
        // passport 凭证串(无会话 token)整串覆盖 = 登出:只取走 LTP0/dy_did 合并进旧登录
        String paste = "acf_stk=stk2; acf_ccn=ccn; LTP0=newlt; dy_did=LOGIN_DID";
        String merged = DouyuService.normalizePastedCookie(old, paste);
        assertTrue(merged.contains("dy_auth=login"));
        assertTrue(merged.contains("LTP0=newlt"));
        assertTrue(merged.contains("acf_stk=old"));
        assertFalse(merged.contains("stk2"));
        // 正常替换(带会话 token)或无旧登录:原样保存
        assertEquals("dy_auth=fresh", DouyuService.normalizePastedCookie(old, "dy_auth=fresh"));
        assertEquals("LTP0=lt; dy_did=d", DouyuService.normalizePastedCookie("", "LTP0=lt; dy_did=d"));
        assertEquals("LTP0=lt", DouyuService.normalizePastedCookie(null, "LTP0=lt"));
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
