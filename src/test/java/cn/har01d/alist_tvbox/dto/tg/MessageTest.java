package cn.har01d.alist_tvbox.dto.tg;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTest {
    @Test
    void shouldCreateMessageFromProviderLink() {
        Message message = Message.fromProvider(
                12,
                "Quark_Share_Channel",
                "名称：2026年6月6日 短剧更新目录12\n描述：测试",
                "https://pan.quark.cn/s/8a16ab9c06b9",
                "2026-06-07T12:00:00Z");

        assertThat(message.getId()).isEqualTo(12);
        assertThat(message.getChannel()).isEqualTo("Quark_Share_Channel");
        assertThat(message.getContent()).contains("短剧更新目录12");
        assertThat(message.getLink()).isEqualTo("https://pan.quark.cn/s/8a16ab9c06b9");
        assertThat(message.getType()).isEqualTo("5");
        assertThat(message.getName()).isEqualTo("2026年6月6日 短剧更新目录12");
        assertThat(message.getTime()).isEqualTo(Instant.parse("2026-06-07T12:00:00Z"));
    }

    @Test
    void shouldUseProviderIdWhenTelegramMessageIdDoesNotFitInt() {
        Message message = Message.fromProvider(
                7,
                4_000_000_000L,
                "channel",
                "名称：测试",
                "https://pan.baidu.com/s/1Zc_e4792cuvucfI-ZZts0Q?pwd=ruub",
                "2026-06-07T12:00:00Z");

        assertThat(message.getId()).isEqualTo(7);
        assertThat(message.getType()).isEqualTo("10");
    }
}
