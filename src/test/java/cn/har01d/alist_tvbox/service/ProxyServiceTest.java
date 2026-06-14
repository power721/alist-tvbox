package cn.har01d.alist_tvbox.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyServiceTest {
    @Test
    void parsePlayUrlIdShouldAcceptIsoSuffix() {
        assertThat(ProxyService.parsePlayUrlId("1@106306.iso")).isEqualTo(106306);
    }
}
