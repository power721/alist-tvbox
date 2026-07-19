package cn.har01d.alist_tvbox.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PluginCompilerCompatibilityTest {
    private final PluginCompilerService service = new PluginCompilerService();

    @Test
    void checkMagnetSpiderCompatibilityShouldPassForCompliantSource() {
        String source = """
                from base.spider import Spider

                class Spider(Spider):
                    def detailContent(self, aid):
                        return {
                            "list": [{
                                "vod_name": aid,
                                "vod_pic": "https://example.invalid/poster.jpg",
                                "vod_play_from": "磁力",
                                "vod_play_url": "正片$magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF"
                            }]
                        }

                    def playerContent(self, flag, id, vipFlags):
                        return {"parse": 0, "url": id}
                """;

        PluginCompilerService.CompatibilityCheckResponse response = service.checkMagnetSpiderCompatibility(
                new PluginCompilerService.CompatibilityCheckRequest(
                        "JavBus",
                        3,
                        "",
                        "javbus_self",
                        source
                )
        );

        assertThat(response.passed()).isTrue();
        assertThat(response.failCount()).isZero();
        assertThat(response.passCount()).isEqualTo(response.items().size());
        assertThat(response.items()).allMatch(item -> "PASS".equals(item.status()));
    }

    @Test
    void checkMagnetSpiderCompatibilityShouldReportMissingRules() {
        String source = """
                //@name:Oops
                from base.spider import Spider

                class Spider(Spider):
                    pass
                """;

        PluginCompilerService.CompatibilityCheckResponse response = service.checkMagnetSpiderCompatibility(
                new PluginCompilerService.CompatibilityCheckRequest(
                        "Oops",
                        1,
                        "",
                        "",
                        source
                )
        );

        assertThat(response.passed()).isFalse();
        assertThat(response.failCount()).isGreaterThan(0);
        assertThat(response.items()).anyMatch(item -> item.code().equals("outer_headers") && item.status().equals("FAIL"));
        assertThat(response.items()).anyMatch(item -> item.code().equals("detail_content") && item.status().equals("FAIL"));
        assertThat(response.items()).anyMatch(item -> item.code().equals("player_content") && item.status().equals("FAIL"));
        assertThat(response.items()).anyMatch(item -> item.code().equals("vod_pic") && item.status().equals("FAIL"));
        assertThat(response.items()).anyMatch(item -> item.code().equals("magnet_flow") && item.status().equals("FAIL"));
    }
}
