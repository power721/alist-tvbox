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
    // 花椒已随 pure_live 3.1.6 下线(feed 实测 0 房间),不再探测
    private static final String[] DEFAULT_PLATFORMS = {"acfun", "inke", "sixroom", "kugoulive", "look", "yy"};
    private static final String[] PLATFORMS = System.getProperty("live.probe.platforms", String.join(",", DEFAULT_PLATFORMS)).split(",");

    private final RestTemplateBuilder builder = new RestTemplateBuilder();
    // 与生产 Spring mapper 同款宽松(忽略未知字段):裸 mapper 严格模式会对斗鱼等响应的多余字段炸 UnrecognizedProperty
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            com.fasterxml.jackson.databind.json.JsonMapper.builder()
                    .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();

    @Test
    void probe() throws Exception {
        for (String platform : PLATFORMS) {
            LivePlatform service = switch (platform.trim()) {
                case "acfun" -> new AcfunService(builder, objectMapper);
                case "inke" -> new InkeService(builder, objectMapper, null);
                case "sixroom" -> new SixRoomService(builder, objectMapper);
                case "kugoulive" -> new KugouLiveService(builder, objectMapper, null);
                case "look" -> new LookLiveService(builder, objectMapper, null);
                case "yy" -> new YyService(builder, objectMapper, null);
                // 老平台冒烟入口:快手主播原生搜索(2753a910 对齐)与斗鱼匿名播放链路
                // (mock 仓库 = 无 Cookie 匿名态,findById 默认 Optional.empty;斗鱼要挂 Jackson2 转换器,
                //  裸 builder 默认 Jackson3 不认 ObjectNode 响应,TelegramServiceTest 同款整表替换)
                case "ks", "kuaishou" -> new KuaishouService(builder, objectMapper);
                case "douyu" -> new DouyuService(
                        builder.messageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(objectMapper)),
                        objectMapper,
                        org.mockito.Mockito.mock(cn.har01d.alist_tvbox.entity.SettingRepository.class));
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
                // 两级目录平台(如 YY 频道→分区):首层是文件夹时下钻一层再探房间列表
                var folder = list.getList().stream().filter(d -> d.getVod_tag() != null && d.getVod_tag().contains("folder")).findFirst();
                if (folder.isPresent()) {
                    var sub = service.list(folder.get().getVod_id(), null, null, 1);
                    System.out.printf("[%s] list(%s): %d rooms, pagecount=%d%n",
                            name, folder.get().getVod_name(), sub.getList().size(), sub.getPagecount());
                }
            }
            if (!home.getList().isEmpty()) {
                var detail = service.detail(home.getList().get(0).getVod_id(), null);
                var first = detail.getList().get(0);
                System.out.printf("[%s] detail: %s | remarks=%s | playFrom=%s | %d entries%n", name, first.getVod_name(),
                        first.getVod_remarks(), first.getVod_play_from(),
                        first.getVod_play_url() == null ? 0 : first.getVod_play_url().split("#").length);
                if (first.getVod_play_url() != null) {
                    for (String entry : first.getVod_play_url().split("#")) {
                        System.out.printf("[%s]   %s%n", name, abbreviate(entry, 150));
                    }
                }
            }
            var search = service.search(System.getProperty("live.probe.keyword", "游戏"));
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
