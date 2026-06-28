package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.util.Utils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HuyaParseServiceTest {

    private final HuyaParseService service = new HuyaParseService(new RestTemplateBuilder());

    @Test
    void buildAntiCodeUsesRotatedUidForNonWap() {
        String fm = Base64.getEncoder().encodeToString("testprefix_placeholder".getBytes(StandardCharsets.UTF_8));
        String antiCode = "fm=" + fm + "&wsTime=65f0c0de&ctype=huya_pc_exe&t=100&fs=0";

        String query = service.buildAntiCode("stream-123", 16909060L, antiCode, 1700000000000L, 0.25d);
        var params = UriComponentsBuilder.newInstance().query(query).build().getQueryParams();

        long seqId = 1700000000000L + 16909060L;
        long rotatedUid = 33752065L;
        String secretHash = Utils.md5(seqId + "|huya_pc_exe|100");
        String wsSecret = Utils.md5("testprefix_" + rotatedUid + "_stream-123_" + secretHash + "_65f0c0de");

        assertEquals(String.valueOf(seqId), params.getFirst("seqid"));
        assertEquals(String.valueOf(rotatedUid), params.getFirst("u"));
        assertEquals(wsSecret, params.getFirst("wsSecret"));
        assertEquals(Utils.encodeUrl(fm), params.getFirst("fm"));
        assertNull(params.getFirst("uid"));
    }

    @Test
    void buildFinalPlayUrlOmitsBlankRatio() {
        String url = service.buildFinalPlayUrl("https://example.com/live/stream.flv", "wsSecret=abc&wsTime=def", "");

        assertEquals("https://example.com/live/stream.flv?wsSecret=abc&wsTime=def&codec=264", url);
    }
}
