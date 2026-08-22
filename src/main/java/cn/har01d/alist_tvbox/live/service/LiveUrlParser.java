package cn.har01d.alist_tvbox.live.service;

import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从官方直播间地址解析平台类型与房间号(www./m. 子域通用,可省略协议)。
 * 只认 host 与首段路径,房间号限制为字母数字组合;分类页/保留路径等误识别
 * 会因平台 detail 拉取不到房间信息而在关注时报错,无需维护路径黑名单。
 */
public final class LiveUrlParser {
    /** 与 LiveFollow.roomId 列宽一致,房间号只允许字母数字,防止拼入外部请求 */
    private static final Pattern ROOM_ID = Pattern.compile("[0-9A-Za-z_-]{1,64}");
    /** 从分享文案中提取带协议的 URL;字符集排除中文等自然语言,但可能吞入尾部标点 */
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
    /** 文案里不带协议的裸域名(www./m./live. 开头) */
    private static final Pattern BARE_URL_IN_TEXT = Pattern.compile("(?:www|m|live)\\.[\\w\\-]+(?:\\.[\\w\\-]+)+(?:/[\\w\\-._~/?#%&=]*)?");

    private LiveUrlParser() {
    }

    /**
     * 从分享文案等混合文本中提取第一个 URL(可无协议),随后剥掉文案紧贴的标点。
     * 手机端分享内容通常带文字包装("【主播】正在直播 https://... 复制打开"),直接解析整段会失败。
     */
    public static String extractUrl(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        Matcher matcher = URL_IN_TEXT.matcher(text);
        String url = matcher.find() ? matcher.group() : null;
        if (url == null) {
            matcher = BARE_URL_IN_TEXT.matcher(text);
            url = matcher.find() ? matcher.group() : null;
        }
        if (url == null) {
            return null;
        }
        while (!url.isEmpty() && ".,;:!?)\\]'\">".indexOf(url.charAt(url.length() - 1)) >= 0) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** @return {platform, roomId};无法识别返回 null */
    public static String[] parse(String url) {
        URI uri = toUri(url);
        if (uri == null) {
            return null;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        // path 以 / 开头,split 首段为空串,须过滤后再取路径段
        String[] segments = uri.getPath() == null ? new String[0]
                : Arrays.stream(uri.getPath().split("/")).filter(StringUtils::isNotEmpty).toArray(String[]::new);
        String roomId = segment(segments, 0);
        if (roomId == null) {
            return null;
        }
        if (isHost(host, "huya.com")) {
            return new String[]{"huya", roomId};
        }
        if (isHost(host, "douyu.com")) {
            return new String[]{"douyu", roomId};
        }
        if (isHost(host, "live.bilibili.com")) {
            return new String[]{"bili", roomId};
        }
        if (isHost(host, "cc.163.com")) {
            // /user/{cuteid} 是主播空间页,cuteid 与房间 id 同值,可当房间号使用
            String userId = "user".equals(roomId) ? segment(segments, 1) : roomId;
            return userId == null ? null : new String[]{"cc", userId};
        }
        if (isHost(host, "live.kuaishou.com") || isHost(host, "live.kuaishou.cn")) {
            // 快手房间页固定为 /u/{主播id},首段 "u" 不是房间号
            String userId = "u".equals(roomId) ? segment(segments, 1) : null;
            return userId == null ? null : new String[]{"ks", userId};
        }
        if (isHost(host, "live.douyin.com")) {
            // /user/{sec_uid} 是主播主页而非房间页,sec_uid 无法按房间号查询
            return "user".equals(roomId) ? null : new String[]{"douyin", roomId};
        }
        if (isHost(host, "twitch.tv")) {
            return new String[]{"twitch", roomId};
        }
        if (isHost(host, "play.sooplive.com") || isHost(host, "afreecatv.com")) {
            return new String[]{"soop", roomId};
        }
        return null;
    }

    /**
     * 由平台与房间号反向构建官方直播间页地址,与 {@link #parse} 的域名规则互逆。
     * 平台未接入或房间号含非法字符时返回 null,调用方需自行降级。
     */
    public static String buildRoomUrl(String platform, String roomId) {
        if (platform == null || roomId == null || !ROOM_ID.matcher(roomId).matches()) {
            return null;
        }
        return switch (platform) {
            case "huya" -> "https://www.huya.com/" + roomId;
            case "douyu" -> "https://www.douyu.com/" + roomId;
            case "bili" -> "https://live.bilibili.com/" + roomId;
            // CC 房间直链 /{id}/ 会被官方前端重定向到首页,主播空间页 /user/{id}/ 可直达且含"Ta的直播房间"入口
            case "cc" -> "https://cc.163.com/user/" + roomId + "/";
            // 快手房间页按主播 id 展示,与 parse 的 /u/{id} 规则一致
            case "ks" -> "https://live.kuaishou.com/u/" + roomId;
            case "douyin" -> "https://live.douyin.com/" + roomId;
            case "twitch" -> "https://www.twitch.tv/" + roomId;
            case "soop" -> "https://play.sooplive.com/" + roomId;
            default -> null;
        };
    }

    /** 分享短链/落地页(需经网络展开):B站 b23.tv、抖音 v.douyin.com 与 iesdouyin 分享页、快手 v.kuaishou.com。 */
    public static boolean isShareLink(String url) {
        URI uri = toUri(url);
        if (uri == null) {
            return false;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return isHost(host, "b23.tv") || isHost(host, "v.douyin.com") || isHost(host, "v.kuaishou.com")
                || isHost(host, "iesdouyin.com") || isHost(host, "amemv.com");
    }

    private static URI toUri(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        String value = url.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isHost(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String segment(String[] segments, int index) {
        if (index >= segments.length) {
            return null;
        }
        String value = segments[index];
        return ROOM_ID.matcher(value).matches() ? value : null;
    }
}
