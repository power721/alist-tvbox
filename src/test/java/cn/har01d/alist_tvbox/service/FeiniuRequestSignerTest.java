package cn.har01d.alist_tvbox.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeiniuRequestSignerTest {
    @Test
    void shouldGenerateExpectedAuthxValue() {
        String authx = FeiniuRequestSigner.buildAuthx(
                "/v/api/v1/item/list",
                "{\"ancestor_guid\":\"636b0918be9a4b50beaab50778554407\",\"page\":1}",
                "376975",
                1777534575506L
        );

        assertThat(authx)
                .isEqualTo("nonce=376975&timestamp=1777534575506&sign=211eaeb89a5d4a15bae4c87af44ed99b");
    }
}
