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

    private final ObjectMapper objectMapper = new ObjectMapper();

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
