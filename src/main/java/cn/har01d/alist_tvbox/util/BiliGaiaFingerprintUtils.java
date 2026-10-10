package cn.har01d.alist_tvbox.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

/**
 * B站 Web 端设备指纹(gaia gateway)工具:程序获取的 buvid3 在服务端没有设备档案,
 * 点赞/投币/收藏/分享等写操作会被风控拒绝(-403 账号异常);
 * 须经 finger/spi 取指纹对并携带本模板向 ExClimbWuzhi 上报一次建立档案。
 */
public final class BiliGaiaFingerprintUtils {
    /** 指纹上报与 payload 设备特征(b8ce 字段)必须一致的用户代理,勿与其他 UA 混用 */
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36 Edg/151.0.0.0";

    /** 真实浏览器(Edge 151 / Win10 / 1920x1080)采集的设备特征快照,混淆键保持原序;动态字段(时间戳 5062/来路 03bf/spm 39c8/设备 _uuid df35)由 buildPayload 替换 */
    private static final String PAYLOAD_JSON = """
            {"3064":1,"5062":"1789442985458","03bf":"https%3A%2F%2Fwww.bilibili.com%2F","39c8":"333.1007.fp.risk","34f1":"","d402":"","654a":"","6e7c":"801x956","3c43":{"2673":0,"5766":24,"6527":0,"7003":1,"807e":1,"b8ce":"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36 Edg/151.0.0.0","641c":0,"07a4":"zh-CN","1c57":32,"0bd0":6,"748e":[1920,1080],"d61f":[1920,1032],"fc9d":-480,"6aa9":"Asia/Shanghai","75b8":1,"3b21":1,"8a1c":0,"d52f":"not available","adca":"Win32","80c9":[["PDF Viewer","Portable Document Format",[["application/pdf","pdf"],["text/pdf","pdf"]]],["Chrome PDF Viewer","Portable Document Format",[["application/pdf","pdf"],["text/pdf","pdf"]]],["Chromium PDF Viewer","Portable Document Format",[["application/pdf","pdf"],["text/pdf","pdf"]]],["Microsoft Edge PDF Viewer","Portable Document Format",[["application/pdf","pdf"],["text/pdf","pdf"]]],["WebKit built-in PDF","Portable Document Format",[["application/pdf","pdf"],["text/pdf","pdf"]]]],"13ab":"4PqbAAAAAElFTkSuQmCC","bfe9":"//TgNIfAAAAAZJREFUAwBde+3wgcxEHQAAAABJRU5ErkJggg==","a3c1":["extensions:ANGLE_instanced_arrays;EXT_blend_minmax;EXT_clip_control;EXT_color_buffer_half_float;EXT_depth_clamp;EXT_disjoint_timer_query;EXT_float_blend;EXT_frag_depth;EXT_polygon_offset_clamp;EXT_shader_texture_lod;EXT_texture_compression_bptc;EXT_texture_compression_rgtc;EXT_texture_filter_anisotropic;EXT_texture_mirror_clamp_to_edge;EXT_sRGB;KHR_parallel_shader_compile;OES_element_index_uint;OES_fbo_render_mipmap;OES_standard_derivatives;OES_texture_float;OES_texture_float_linear;OES_texture_half_float;OES_texture_half_float_linear;OES_vertex_array_object;WEBGL_blend_func_extended;WEBGL_color_buffer_float;WEBGL_compressed_texture_s3tc;WEBGL_compressed_texture_s3tc_srgb;WEBGL_debug_renderer_info;WEBGL_debug_shaders;WEBGL_depth_texture;WEBGL_draw_buffers;WEBGL_lose_context;WEBGL_multi_draw;WEBGL_polygon_mode","webgl aliased line width range:[1, 1]","webgl aliased point size range:[1, 1024]","webgl alpha bits:8","webgl antialiasing:yes","webgl blue bits:8","webgl depth bits:24","webgl green bits:8","webgl max anisotropy:16","webgl max combined texture image units:32","webgl max cube map texture size:16384","webgl max fragment uniform vectors:1024","webgl max render buffer size:16384","webgl max texture image units:16","webgl max texture size:16384","webgl max varying vectors:30","webgl max vertex attribs:16","webgl max vertex texture image units:16","webgl max vertex uniform vectors:4095","webgl max viewport dims:[32767, 32767]","webgl red bits:8","webgl renderer:WebKit WebGL","webgl shading language version:WebGL GLSL ES 1.0 (OpenGL ES GLSL ES 1.0 Chromium)","webgl stencil bits:0","webgl vendor:WebKit","webgl version:WebGL 1.0 (OpenGL ES 2.0 Chromium)","webgl unmasked vendor:Google Inc. (NVIDIA)","webgl unmasked renderer:ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Laptop GPU (0x00002520) Direct3D11 vs_5_0 ps_5_0, D3D11)","webgl vertex shader high float precision:23","webgl vertex shader high float precision rangeMin:127","webgl vertex shader high float precision rangeMax:127","webgl vertex shader medium float precision:23","webgl vertex shader medium float precision rangeMin:127","webgl vertex shader medium float precision rangeMax:127","webgl vertex shader low float precision:23","webgl vertex shader low float precision rangeMin:127","webgl vertex shader low float precision rangeMax:127","webgl fragment shader high float precision:23","webgl fragment shader high float precision rangeMin:127","webgl fragment shader high float precision rangeMax:127","webgl fragment shader medium float precision:23","webgl fragment shader medium float precision rangeMin:127","webgl fragment shader medium float precision rangeMax:127","webgl fragment shader low float precision:23","webgl fragment shader low float precision rangeMin:127","webgl fragment shader low float precision rangeMax:127","webgl vertex shader high int precision:0","webgl vertex shader high int precision rangeMin:31","webgl vertex shader high int precision rangeMax:30","webgl vertex shader medium int precision:0","webgl vertex shader medium int precision rangeMin:31","webgl vertex shader medium int precision rangeMax:30","webgl vertex shader low int precision:0","webgl vertex shader low int precision rangeMin:31","webgl vertex shader low int precision rangeMax:30","webgl fragment shader high int precision:0","webgl fragment shader high int precision rangeMin:31","webgl fragment shader high int precision rangeMax:30","webgl fragment shader medium int precision:0","webgl fragment shader medium int precision rangeMin:31","webgl fragment shader medium int precision rangeMax:30","webgl fragment shader low int precision:0","webgl fragment shader low int precision rangeMin:31","webgl fragment shader low int precision rangeMax:30"],"6bc5":"Google Inc. (NVIDIA)~ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Laptop GPU (0x00002520) Direct3D11 vs_5_0 ps_5_0, D3D11)","ed31":0,"72bd":0,"097b":0,"52cd":[10,0,0],"a658":["Arial","Arial Black","Arial Narrow","Calibri","Cambria","Cambria Math","Comic Sans MS","Consolas","Courier","Courier New","Georgia","Helvetica","Impact","Lucida Console","Lucida Sans Unicode","Microsoft Sans Serif","MS Gothic","MS PGothic","MS Sans Serif","MS Serif","Palatino Linotype","Segoe Print","Segoe Script","Segoe UI","Segoe UI Light","Segoe UI Semibold","Segoe UI Symbol","Tahoma","Times","Times New Roman","Trebuchet MS","Verdana","Wingdings"],"d02f":"124.04347527516074"},"54ef":"{\\"b_ut\\":\\"5\\",\\"home_version\\":\\"V8\\",\\"in_new_ab\\":true,\\"ab_version\\":{\\"for_ai_home_version\\":\\"V8\\",\\"rcmd_timeout_config\\":\\"550\\"},\\"ab_split_num\\":{\\"for_ai_home_version\\":111,\\"rcmd_timeout_config\\":65},\\"uniq_page_id\\":\\"682530328781\\",\\"is_modern\\":true}","8b94":"","df35":"73A2A10D2-D1052-10D3C-2978-10AFBB4A27D91021955infoc","07a4":"zh-CN","5f45":null,"db46":0}""";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BiliGaiaFingerprintUtils() {
    }

    /** 生成浏览器形态的 _uuid:9-4-5-4-12 位大写十六进制 + 6 位数字 + infoc 后缀 */
    public static String generateDeviceUuid() {
        StringBuilder hex = new StringBuilder(36);
        for (int i = 0; i < 18; i++) {
            hex.append(String.format("%02X", RANDOM.nextInt(256)));
        }
        return hex.substring(0, 9) + "-" + hex.substring(9, 13) + "-" + hex.substring(13, 18)
                + "-" + hex.substring(18, 22) + "-" + hex.substring(22, 34)
                + (100000 + RANDOM.nextInt(900000)) + "infoc";
    }

    /** 替换模板动态字段并紧凑序列化,作为 ExClimbWuzhi 请求体 payload 字段的值(JSON 字符串) */
    public static String buildPayload(String deviceUuid) {
        try {
            ObjectNode payload = (ObjectNode) MAPPER.readTree(PAYLOAD_JSON);
            payload.put("5062", String.valueOf(System.currentTimeMillis()));
            payload.put("03bf", "https%3A%2F%2Fwww.bilibili.com%2F");
            payload.put("39c8", "333.1007.fp.risk");
            payload.put("df35", deviceUuid);
            ObjectNode body = MAPPER.createObjectNode();
            body.put("payload", MAPPER.writeValueAsString(payload));
            return body.toString();
        } catch (Exception e) {
            throw new IllegalStateException("build gaia payload failed", e);
        }
    }

    /**
     * 页面行为/AB 痕迹字段:真实浏览器 Cookie 的常态字段(点赞等写操作的风控背景信号),
     * 形态对齐 bilibili-API-collect #933 建档样例,仅作 Cookie 补全、无操作语义。
     */
    public static List<String> pageBehaviorCookies() {
        return List.of(
                "innersign=0",
                "i-wanna-go-back=-1",
                "b_ut=5",
                "enable_web_push=DISABLE",
                "header_theme_version=undefined",
                "home_feed_column=4",
                "browser_resolution=801-956");
    }

    /** 生成浏览器形态的 b_lsid:秒级时间戳大写十六进制 _ 11 位随机大写十六进制(样例 9910433CB_18CF260AB89) */
    public static String generateBLsid() {
        StringBuilder random = new StringBuilder(11);
        for (int i = 0; i < 11; i++) {
            random.append("0123456789ABCDEF".charAt(RANDOM.nextInt(16)));
        }
        return Long.toHexString(System.currentTimeMillis() / 1000).toUpperCase() + "_" + random;
    }

    /**
     * 计算 buvid_fp(32 位小写 hex):真值为浏览器 fingerprintjs2 的 murmur hash,
     * 但上报 payload 只含 canvas/webgl 尾迹、服务端无从重算比对,自洽稳定即可(与建档设备特征同源)。
     */
    public static String computeBuvidFp(String buvid3) {
        String material = buvid3 + USER_AGENT
                + "Google Inc. (NVIDIA)"
                + "ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Laptop GPU (0x00002520) Direct3D11 vs_5_0 ps_5_0, D3D11)"
                + "Win32";
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("compute buvid_fp failed", e);
        }
    }

    /** GenWebTicket 契约(2026-10 实测):key_id=ec02,hexsign=HMAC_SHA256(密钥, "ts"+秒级时间戳);旧式 key 参数已 400 */
    private static final String TICKET_HMAC_KEY = "XgwSnGZ1p";
    private static final String TICKET_API = "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket";

    /** 构造 GenWebTicket 签名 URL,换取写操作风控因子 bili_ticket(JWT,3 天有效) */
    public static String buildGenWebTicketUrl(String csrf, long epochSeconds) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TICKET_HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String hexSign = HexFormat.of().formatHex(mac.doFinal(("ts" + epochSeconds).getBytes(StandardCharsets.UTF_8)));
            return TICKET_API + "?key_id=ec02&hexsign=" + hexSign + "&context[ts]=" + epochSeconds
                    + "&csrf=" + StringUtils.defaultString(csrf);
        } catch (Exception e) {
            throw new IllegalStateException("build GenWebTicket url failed", e);
        }
    }
}
