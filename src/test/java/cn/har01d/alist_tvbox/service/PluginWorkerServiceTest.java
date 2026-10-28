package cn.har01d.alist_tvbox.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T4 服务端插件运行时:真 python3 子进程 + Atvp 装载 raw 假插件的全链路
 * (init/home/category/detail/search/play/错误响应沿用 id)。无 python3 环境跳过。
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class PluginWorkerServiceTest {

    // 复刻生产形态:全局 mapper 带 pretty-print 缩进输出(行协议撕裂事故的根源),服务内部必须自行压缩
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    @Test
    void fullRoundTripWithRealPythonWorker() throws Exception {
        PluginWorkerService service = new PluginWorkerService(objectMapper);
        assumeTrue(service.isAvailable(), "python3 not available, skip");

        Path spider = Files.createTempFile("t4-fake-spider", ".py");
        spider.toFile().deleteOnExit();
        Files.writeString(spider, """
                # -*- coding: utf-8 -*-
                import json

                class Spider:
                    def init(self, extend):
                        return ""

                    def getName(self):
                        return "fake"

                    def homeContent(self, filter):
                        return json.dumps({"class": [{"type_id": "1", "type_name": "分类一"}]}, ensure_ascii=False)

                    def categoryContent(self, tid, pg, filter, extend):
                        return json.dumps({"list": [{"vod_id": "v1", "vod_name": "片"}]}, ensure_ascii=False)

                    def detailContent(self, ids):
                        return json.dumps({"list": [{"vod_id": ids[0], "vod_name": "详情"}]}, ensure_ascii=False)

                    def searchContent(self, key, quick, pg="1"):
                        return json.dumps({"list": [{"vod_id": "v1", "vod_name": key + ":" + str(pg)}]}, ensure_ascii=False)

                    def playerContent(self, flag, id, vipFlags):
                        return json.dumps({"parse": 0, "url": "https://example.com/v.mp4"}, ensure_ascii=False)
                """);
        String extend = Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(Map.of(
                "source", spider.toString(), "raw", true, "api", "http://localhost:4567", "token", "tok")));

        JsonNode home = service.call("smoke", extend, "home", Map.of());
        assertTrue(home.toString().contains("分类一"));

        JsonNode search = service.call("smoke", extend, "search", Map.of("wd", "醒来", "pg", "2"));
        assertTrue(search.toString().contains("醒来:2"));

        JsonNode play = service.call("smoke", extend, "play", Map.of("play", "p1"));
        assertTrue(play.path("url").asText("").contains("example.com") || play.toString().contains("example.com"));

        service.shutdown();
    }

    @Test
    void backgroundThreadPrintsDoNotBreakProtocol() throws Exception {
        // 线上回归:Atvp 预热守护等后台线程的裸 print 会混进 stdout——协议输出必须与
        // fd1 物理隔离,且 Java 侧跳过杂行继续等本请求响应
        PluginWorkerService service = new PluginWorkerService(objectMapper);
        assumeTrue(service.isAvailable(), "python3 not available, skip");
        Path spider = Files.createTempFile("t4-noisy-spider", ".py");
        spider.toFile().deleteOnExit();
        Files.writeString(spider, """
                # -*- coding: utf-8 -*-
                import json, sys, threading, time

                def _noise():
                    while True:
                        print("background noise", file=sys.stdout, flush=True)
                        time.sleep(0.05)

                class Spider:
                    def init(self, extend):
                        threading.Thread(target=_noise, daemon=True).start()
                        return ""

                    def getName(self):
                        return "noisy"

                    def homeContent(self, filter):
                        time.sleep(0.3)  # 让噪声线程有机会打印
                        return json.dumps({"class": [{"type_id": "1", "type_name": "ok"}]}, ensure_ascii=False)
                """);
        String extend = Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(Map.of(
                "source", spider.toString(), "raw", true, "api", "http://localhost:4567")));

        JsonNode home = service.call("noisy", extend, "home", Map.of());
        assertTrue(home.toString().contains("ok"));
        // 第二次调用同样干净(复用 worker,噪声线程持续打印中)
        assertTrue(service.call("noisy", extend, "home", Map.of()).toString().contains("ok"));
        service.shutdown();
    }

    @Test
    void cencEncryptedPlayRewritesToProxyAndDecrypts() throws Exception {
        // 红果密文流端到端:假插件起本地密文上游(playerContent 返回 #hg-cenc= 片段 URL),
        // worker 建解密会话改写为 /t4cenc 桥地址,Java forward 到 worker 本地代理,
        // 断言收到的流包含明文模式(密文=明文^CTR 流,含明文即证明真解密)
        PluginWorkerService service = new PluginWorkerService(objectMapper);
        assumeTrue(service.isAvailable(), "python3 not available, skip");
        Path spider = Files.createTempFile("t4-cenc-spider", ".py");
        spider.toFile().deleteOnExit();
        Files.writeString(spider, CENC_SMOKE_SPIDER);
        String extend = Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(Map.of(
                "source", spider.toString(), "raw", true, "api", "http://localhost:4567")));

        com.fasterxml.jackson.databind.JsonNode play = service.call(
                "cenc", extend, "play", Map.of("play", "p1", "proxy_base", "http://atv.test/t4cenc/tok/cenc"));
        String url = play.path("url").asText();
        assertTrue(url.startsWith("http://atv.test/t4cenc/tok/cenc/cenc/"), url);

        String pathAndQuery = url.substring("http://atv.test/t4cenc/tok/cenc".length());
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", pathAndQuery);
        request.addHeader("Range", "bytes=0-65535");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        service.forward("cenc", pathAndQuery, request, response);

        assertEquals(206, response.getStatus());
        byte[] body = response.getContentAsByteArray();
        // 明文视频样本模式:(i*7+1)&0xFF,97 字节——密文里不存在该序列,命中即解密成功
        boolean found = false;
        for (int i = 0; i + 97 <= body.length; i++) {
            boolean match = true;
            for (int j = 0; j < 97; j++) {
                if ((body[i + j] & 0xFF) != ((j * 7 + 1) & 0xFF)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                found = true;
                break;
            }
        }
        assertTrue(found, "decrypted plaintext pattern not found in " + body.length + " bytes");
        service.shutdown();
    }

    /** 合成整段 CENC MP4(单 encv trak,确定性 key 与明文)+ 本地 Range 上游 + playerContent 出密文片段 URL。 */
    private static final String CENC_SMOKE_SPIDER = """
            # -*- coding: utf-8 -*-
            import base64, json, struct, threading
            from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
            from Crypto.Cipher import AES
            
            KEY = bytes.fromhex("86c22eed79003130e78191b09b7588e5")
            PLAIN = bytes((i * 7 + 1) & 0xFF for i in range(97))
            IV = bytes(range(0x10, 0x18))

            def _ctr(key, iv, length):
                cipher = AES.new(key, AES.MODE_CTR, nonce=b"", initial_value=int.from_bytes(iv.ljust(16, b"\\0"), "big"))
                return cipher.encrypt(b"\\0" * length)

            def _box(fourcc, payload):
                return struct.pack(">I", len(payload) + 8) + fourcc + payload

            def _full_box(fourcc, version, flags, payload):
                return _box(fourcc, bytes([version]) + flags.to_bytes(3, "big") + payload)

            def _make_spade_for_key(key):
                hexbytes = key.hex().encode()
                after = [0x30] + list(hexbytes)
                value = bytearray(64)
                va, vb = 85, 246
                for index in range(33):
                    previous = va if index & 1 else vb
                    before = ((after[index] + 21 + index.bit_count()) & 0xFF) ^ previous
                    value[index] = before
                    if index & 1:
                        va = before
                    else:
                        vb = before
                header = value[0] ^ value[1] ^ 64
                return base64.b64encode(bytes([header]) + bytes(value[:63]) + bytes(16)).decode()

            def _media():
                enc = bytes(a ^ b for a, b in zip(PLAIN, _ctr(KEY, IV, len(PLAIN))))
                def trak(sample_offset, size, aux_offset):
                    stsz = _full_box(b"stsz", 0, 0, struct.pack(">II", 0, 1) + struct.pack(">I", size))
                    stco = _full_box(b"stco", 0, 0, struct.pack(">I", 1) + struct.pack(">I", sample_offset))
                    stsc = _full_box(b"stsc", 0, 0, struct.pack(">I", 1) + struct.pack(">III", 1, 1, 1))
                    saiz = _full_box(b"saiz", 0, 0, bytes([0]) + struct.pack(">I", 1) + bytes([8]))
                    saio = _full_box(b"saio", 0, 0, struct.pack(">I", 1) + struct.pack(">I", aux_offset))
                    sinf = _box(b"sinf", _box(b"frma", b"hvc1"))
                    entry = _box(b"encv", b"\\0" * 8 + sinf)
                    stbl = _box(b"stbl", entry + stsz + stco + stsc + saiz + saio)
                    return _box(b"trak", _box(b"tkhd", b"\\0" * 92) + stbl)
                def moov(video_offset, aux_offset):
                    return _box(b"moov", _box(b"mvhd", b"\\0" * 100) + trak(video_offset, len(enc), aux_offset))
                ftyp = _box(b"ftyp", b"isom\\0\\0\\2\\0isom")
                moov_size = len(moov(0, 0))
                mdat_offset = len(ftyp) + moov_size + 8
                aux_offset = mdat_offset + len(enc)
                data = ftyp + moov(mdat_offset, aux_offset) + _box(b"mdat", enc) + IV
                return data

            MEDIA = _media()
            SPADE = _make_spade_for_key(KEY)

            class _Upstream(BaseHTTPRequestHandler):
                protocol_version = "HTTP/1.1"
                def log_message(self, fmt, *args):
                    pass
                def do_GET(self):
                    rng = self.headers.get("Range") or ""
                    if rng == "bytes=0-0":
                        body = b"x"
                        self.send_response(206)
                        self.send_header("Content-Range", "bytes 0-0/%d" % len(MEDIA))
                    else:
                        start = int(rng[6:].split("-", 1)[0]) if rng else 0
                        end = int(rng[6:].split("-", 1)[1]) if rng and "-" in rng[6:] else len(MEDIA) - 1
                        body = MEDIA[start:end + 1]
                        self.send_response(206)
                        self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, len(MEDIA)))
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)

            _server = ThreadingHTTPServer(("127.0.0.1", 0), _Upstream)
            threading.Thread(target=_server.serve_forever, daemon=True).start()
            UPSTREAM = "http://127.0.0.1:%d/media.mp4" % _server.server_address[1]

            class Spider:
                def init(self, extend):
                    return ""
                def getName(self):
                    return "cenc-smoke"
                def playerContent(self, flag, id, vipFlags):
                    encoded = base64.urlsafe_b64encode(json.dumps({"spade_a": SPADE}, separators=(",", ":")).encode()).decode()
                    return json.dumps({"parse": 0, "url": UPSTREAM + "#hg-cenc=" + encoded}, ensure_ascii=False)
            """;

    @Test
    void pluginRuntimeUnavailableDegrades() {
        PluginWorkerService service = new PluginWorkerService(objectMapper);
        ReflectionTestUtils.setField(service, "available", new java.util.concurrent.atomic.AtomicReference<>(Boolean.FALSE));

        cn.har01d.alist_tvbox.exception.BadGatewayException ex = assertThrows(
                cn.har01d.alist_tvbox.exception.BadGatewayException.class,
                () -> service.call("any", "", "home", Map.of()));
        assertTrue(ex.getMessage().contains("python3"));
    }

    @Test
    void initFailureSurfacesMessage() throws Exception {
        PluginWorkerService service = new PluginWorkerService(objectMapper);
        assumeTrue(service.isAvailable(), "python3 not available, skip");
        // source 指向不存在的密文地址:Atvp init 报错须透出(BadGateway),而非挂死
        String extend = Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(Map.of(
                "source", "http://127.0.0.1:1/none.txt", "api", "http://localhost:4567")));

        assertThrows(cn.har01d.alist_tvbox.exception.BadGatewayException.class,
                () -> service.call("broken", extend, "home", Map.of()));
        service.shutdown();
    }
}
