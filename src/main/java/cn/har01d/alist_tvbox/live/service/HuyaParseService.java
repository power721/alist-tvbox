package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.live.model.HuyaGetTokenReq;
import cn.har01d.alist_tvbox.live.model.HuyaGetTokenResp;
import cn.har01d.alist_tvbox.live.model.HuyaWup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Service
public class HuyaParseService {
    private static final String WUP_URL = "https://wup.huya.com";
    public static final String HY_SDK_UA = "HYSDK(Windows, 30000002)_APP(pc_exe&7060000&official)_SDK(trans&2.32.3.5646)";

    private final RestTemplate restTemplate;

    public HuyaParseService(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .defaultHeader("User-Agent", HY_SDK_UA)
                .build();
    }

    public String getUa() {
        return HY_SDK_UA;
    }

    public String getTrueUrl(String playUrl) {
        UriComponents components = UriComponentsBuilder.fromUriString(playUrl).build();
        String cdn = components.getQueryParams().getFirst("cdn");
        String streamName = components.getQueryParams().getFirst("name");
        String ratio = components.getQueryParams().getFirst("ratio");
        String puid = components.getQueryParams().getFirst("uid");
        var tokenInfoReq = new HuyaWup();
        tokenInfoReq.tarsServantRequest.setServantName("liveui");
        tokenInfoReq.tarsServantRequest.setFunctionName("getCdnTokenInfo");
        tokenInfoReq.uniAttribute.put("tReq", new HuyaGetTokenReq(playUrl, cdn, streamName, Long.parseLong(puid)));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.REFERER, "https://www.huya.com/");
        HttpEntity<byte[]> entity = new HttpEntity<>(tokenInfoReq.encode(), headers);
        ResponseEntity<byte[]> response = restTemplate.exchange(WUP_URL, HttpMethod.POST, entity, new ParameterizedTypeReference<byte[]>() {
        });
        HuyaWup res = new HuyaWup();
        res.decode(response.getBody());
        HuyaGetTokenResp tokenResp = res.uniAttribute.getByClass("tRsp", new HuyaGetTokenResp());
        log.debug("response: {}", tokenResp);
        String query = tokenResp.getFlvAntiCode() + "&codec=264&ratio=" + ratio;
        components = UriComponentsBuilder.fromUriString(tokenResp.getUrl())
                .replaceQuery(query)
                .build();
        String url = components.toUriString();
        log.debug("url: {}", url);
        return url;
    }
}
