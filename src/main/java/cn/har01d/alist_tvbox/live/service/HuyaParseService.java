package cn.har01d.alist_tvbox.live.service;

import cn.har01d.alist_tvbox.live.model.HuyaGetTokenExReq;
import cn.har01d.alist_tvbox.live.model.HuyaGetTokenExResp;
import cn.har01d.alist_tvbox.live.model.HuyaUserId;
import cn.har01d.alist_tvbox.live.model.HuyaWup;
import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

@Slf4j
@Service
public class HuyaParseService {
    private static final String WUP_URL = "http://wup.huya.com";
    private static final String HUYA_PC_UA = "pc_exe&7060000&official";
    public static final String HY_SDK_UA = "HYSDK(Windows,30000002)_APP(pc_exe&7080000&official)_SDK(trans&2.34.0.5795)";

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
        String streamName = components.getQueryParams().getFirst("name");
        String ratio = components.getQueryParams().getFirst("ratio");
        long presenterUid = getPresenterUid(components.getQueryParams().getFirst("uid"), streamName);
        String antiCode = getCdnTokenInfoEx(streamName);
        antiCode = buildAntiCode(streamName, presenterUid, antiCode);
        String baseUrl = UriComponentsBuilder.fromUriString(playUrl)
                .replaceQuery(null)
                .build()
                .toUriString();
        String url = buildFinalPlayUrl(baseUrl, antiCode, ratio);
        log.debug("url: {}", url);
        return url;
    }

    String buildFinalPlayUrl(String baseUrl, String antiCode, String ratio) {
        StringBuilder query = new StringBuilder(antiCode).append("&codec=264");
        if (StringUtils.isNotBlank(ratio)) {
            query.append("&ratio=").append(ratio);
        }
        return UriComponentsBuilder.fromUriString(baseUrl)
                .replaceQuery(query.toString())
                .build()
                .toUriString();
    }

    String buildAntiCode(String stream, long presenterUid, String antiCode) {
        return buildAntiCode(stream, presenterUid, antiCode, System.currentTimeMillis(), new Random().nextDouble());
    }

    String buildAntiCode(String stream, long presenterUid, String antiCode, long currentTimeMillis, double randomValue) {
        Map<String, String> antiCodeMap = parseQueryString(antiCode);
        if (!antiCodeMap.containsKey("fm")) {
            return antiCode;
        }

        String ctype = antiCodeMap.getOrDefault("ctype", "huya_pc_exe");
        int platformId = parseInt(antiCodeMap.get("t"), 0);
        boolean isWap = platformId == 103;
        long seqId = presenterUid + currentTimeMillis;
        long convertUid = rotl64(presenterUid);
        long calcUid = isWap ? presenterUid : convertUid;
        String wsTime = antiCodeMap.get("wsTime");
        String fm = antiCodeMap.get("fm");
        String secretPrefix = new String(Base64.getDecoder().decode(fm), StandardCharsets.UTF_8).split("_")[0];
        String secretHash = Utils.md5(seqId + "|" + ctype + "|" + platformId);
        String wsSecret = Utils.md5(secretPrefix + "_" + calcUid + "_" + stream + "_" + secretHash + "_" + wsTime);
        long ct = (long) ((Long.parseLong(wsTime, 16) + randomValue) * 1000);
        long uuid = (long) ((((ct % 1e10) + randomValue) * 1e3) % 0xffffffffL);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("wsSecret", wsSecret);
        result.put("wsTime", wsTime);
        result.put("seqid", String.valueOf(seqId));
        result.put("ctype", ctype);
        result.put("ver", "1");
        result.put("fs", antiCodeMap.get("fs"));
        result.put("fm", Utils.encodeUrl(fm));
        result.put("t", String.valueOf(platformId));
        if (isWap) {
            result.put("uid", String.valueOf(presenterUid));
            result.put("uuid", String.valueOf(uuid));
        } else {
            result.put("u", String.valueOf(convertUid));
        }
        return buildQueryString(result);
    }

    long rotl64(long value) {
        long low = value & 0xffffffffL;
        long rotatedLow = ((low << 8) | (low >> 24)) & 0xffffffffL;
        long high = value & ~0xffffffffL;
        return high | rotatedLow;
    }

    private long getPresenterUid(String uid, String streamName) {
        if (StringUtils.isNotBlank(uid)) {
            return Long.parseLong(uid);
        }
        if (StringUtils.isNotBlank(streamName) && streamName.contains("-")) {
            return parseLong(streamName.substring(0, streamName.indexOf('-')), 0L);
        }
        return 0L;
    }

    private String getCdnTokenInfoEx(String streamName) {
        HuyaWup tokenInfoReq = new HuyaWup();
        tokenInfoReq.tarsServantRequest.setServantName("liveui");
        tokenInfoReq.tarsServantRequest.setFunctionName("getCdnTokenInfoEx");

        HuyaUserId userId = new HuyaUserId();
        userId.setSHuYaUA(HUYA_PC_UA);

        HuyaGetTokenExReq request = new HuyaGetTokenExReq();
        request.setSStreamName(streamName);
        request.setTId(userId);
        tokenInfoReq.uniAttribute.put("tReq", request);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, "https://m.huya.com");
        headers.set(HttpHeaders.REFERER, "https://m.huya.com/");
        HttpEntity<byte[]> entity = new HttpEntity<>(tokenInfoReq.encode(), headers);
        ResponseEntity<byte[]> response = restTemplate.exchange(WUP_URL, HttpMethod.POST, entity, byte[].class);

        HuyaWup res = new HuyaWup();
        res.decode(response.getBody());
        HuyaGetTokenExResp tokenResp = res.uniAttribute.getByClass("tRsp", new HuyaGetTokenExResp());
        log.debug("response: {}", tokenResp);
        return tokenResp.getSFlvToken();
    }

    private Map<String, String> parseQueryString(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int index = pair.indexOf('=');
            String key = index >= 0 ? pair.substring(0, index) : pair;
            String value = index >= 0 ? pair.substring(index + 1) : "";
            result.put(urlDecode(key), urlDecode(value));
        }
        return result;
    }

    private String buildQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((key, value) -> {
            if (StringUtils.isNotBlank(value)) {
                if (!sb.isEmpty()) {
                    sb.append("&");
                }
                sb.append(key).append("=").append(value);
            }
        });
        return sb.toString();
    }

    private String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private long parseLong(String value, long defaultValue) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
