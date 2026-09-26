package cn.har01d.alist_tvbox.live.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.restclient.RestTemplateBuilder;

/**
 * pure_live 同源新平台端到端探针(诊断用,默认不跑):真实驱动六个新 Service 的
 * home/category/list/search/detail 全链路,验证接口存活与播放地址产出。
 * <pre>
 * mvn test -Dtest=NewLivePlatformProbeTest -Dlive.probe=1 [-Dlive.probe.platforms=acfun,kugoulive]
 * </pre>
 */
@EnabledIfSystemProperty(named = "live.probe", matches = "1")
class NewLivePlatformProbeTest {
    private static final String[] DEFAULT_PLATFORMS = {"acfun", "inke", "huajiao", "sixroom", "kugoulive", "look"};
    private static final String[] PLATFORMS = System.getProperty("live.probe.platforms", String.join(",", DEFAULT_PLATFORMS)).split(",");

    private final RestTemplateBuilder builder = new RestTemplateBuilder();
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    void probe() throws Exception {
        for (String platform : PLATFORMS) {
            LivePlatform service = switch (platform.trim()) {
                case "acfun" -> new AcfunService(builder, objectMapper);
                case "inke" -> new InkeService(builder, objectMapper, null);
                case "huajiao" -> new HuajiaoService(builder, objectMapper);
                case "sixroom" -> new SixRoomService(builder, objectMapper);
                case "kugoulive" -> new KugouLiveService(builder, objectMapper, null);
                case "look" -> new LookLiveService(builder, objectMapper);
                default -> throw new IllegalArgumentException("unknown platform: " + platform);
            };
            probePlatform(service);
        }
    }

    private void probePlatform(LivePlatform service) throws Exception {
        String name = service.getName();
        try {
            var home = service.home();
            System.out.printf("[%s] home: %d rooms%n", name, home.getList().size());
            var categories = service.category();
            System.out.printf("[%s] category: %d%n", name, categories.getCategories().size());
            int withCover = (int) categories.getCategories().stream().filter(c -> c.getCover() != null && !c.getCover().isEmpty()).count();
            System.out.printf("[%s] category covers: %d/%d (first=%s)%n", name, withCover, categories.getCategories().size(),
                    abbreviate(categories.getCategories().stream().map(c -> c.getCover()).filter(c -> c != null && !c.isEmpty()).findFirst().orElse("-"), 60));
            if (!categories.getCategories().isEmpty()) {
                var list = service.list(categories.getCategories().get(0).getType_id(), null, null, 1);
                System.out.printf("[%s] list(%s): %d rooms, pagecount=%d%n",
                        name, categories.getCategories().get(0).getType_name(), list.getList().size(), list.getPagecount());
            }
            if (!home.getList().isEmpty()) {
                var detail = service.detail(home.getList().get(0).getVod_id(), null);
                var first = detail.getList().get(0);
                System.out.printf("[%s] detail: %s | playFrom=%s | urls=%s%n", name, first.getVod_name(),
                        first.getVod_play_from(), abbreviate(first.getVod_play_url(), 120));
            }
            var search = service.search("游戏");
            System.out.printf("[%s] search: %d results%n", name, search.getList().size());
        } catch (Exception e) {
            System.out.printf("[%s] FAILED: %s%n", name, e);
        }
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
