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
