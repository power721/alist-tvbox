package cn.har01d.alist_tvbox.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class TextUtils {

    private static final List<String> NUMBERS = Arrays.asList("零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十");
    private static final Pattern NUMBER = Pattern.compile("第(\\d+\\.?\\d*)季");
    private static final Pattern NUMBER1 = Pattern.compile("\\d+");
    private static final Pattern NUMBER2 = Pattern.compile("(第.+季)");
    private static final Pattern NUMBER3 = Pattern.compile(" ?(S\\d{1,2})");
    private static final Pattern NUMBER4 = Pattern.compile("(\\.?\\d+集全?)");
    private static final Pattern NUMBER5 = Pattern.compile("(\\.[0-9-]+季(\\+番外)?)");
    private static final Pattern NUMBER6 = Pattern.compile("(.更新至\\d+)");
    private static final Pattern TRAILING_EPISODE_STATUS = Pattern.compile(
            "\\s*(?:更新至|更至|更新|全)?\\s*\\d+\\s*[集期话](?:全|完结)?\\s*$");
    private static final Pattern RELEASE_YEAR = Pattern.compile("[（(]((?:19|20)\\d{2})[）)]");
    private static final Pattern TRAILING_BRACKET_METADATA = Pattern.compile("\\s*【[^】]*】\\s*$");
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private static final Pattern NAME1 = Pattern.compile("^【(.+)】$");
    private static final Pattern NAME2 = Pattern.compile("^\\w (.+)\\s+\\(\\d{4}\\).*");
    private static final Pattern NAME3 = Pattern.compile("^\\w (.+)\\.\\d{4} .+");
    // 新增：S01E01, 1x01 等季集格式
    private static final Pattern SEASON_EPISODE = Pattern.compile("[.\\s]?(S\\d{1,2})E(\\d{2,3})");
    private static final Pattern SEASON_EPISODE2 = Pattern.compile("[.\\s]?(\\d{1,2})x(\\d{2,3})");
    // 新增：流媒体平台标签
    private static final Pattern STREAMING_PLATFORM = Pattern.compile("\\[(Netflix|Disney\\+|Hulu|HBO|Amazon\\+|Prime|Apple\\+|爱奇艺|腾讯|优酷|哔哩|Bilibili|芒果TV)]");
    // 元数据 id 目录标记(追剧转存目录 metaIdTag / 削刮命名):豆瓣 dbid / TMDB tmdbid / Bangumi bgmid,方括号或花括号
    private static final Pattern META_ID_TAG = Pattern.compile("[\\[{](dbid|tmdbid|bgmid)-(\\d+)[\\]}]");
    // 新增：编码和音视频格式
    private static final Pattern AV_FORMAT = Pattern.compile("\\.(HEVC|H265|H264|x265|x264|AVC|AV1|VP9|VC-1|mp4|mkv|MPEG-2|Xvid)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUDIO_FORMAT = Pattern.compile("(DTS|DDP|DD\\+|AC3|AAC|FLAC|MP3|Opus|Atmos|TrueHD)([.\\s]?\\d+\\.?\\d*)?", Pattern.CASE_INSENSITIVE);
    // 新增：分辨率标签
    private static final Pattern RESOLUTION = Pattern.compile("\\.?(\\d{3,4}p|4K|2K|8K|UHD|FHD|QHD|SDR|HDR10|HDR|HD\\+?|Dolby\\s*Vision|DoVi)", Pattern.CASE_INSENSITIVE);
    // 新增：发布组和字幕组
    private static final Pattern RELEASE_GROUP = Pattern.compile("\\[([^]]+(字幕组|发布组|制作|Subs|Rip))]");
    // 行首装饰性符号：空白、间隔号(U+00B7/U+0387/U+30FB)、不可见变体选择符(U+FE0F/U+FE0E)、
    // ZWJ 以及 emoji/符号区段。Telegram 频道常用 ·✅✅✅ 之类前缀，由 stripLeadingNoise 统一剥离。
    private static final Pattern LEADING_NOISE = Pattern.compile(
            "^[\\s\\u00B7\\u0387\\u30FB\\uFE0F\\uFE0E\\u200D\\u2600-\\u27BF\\x{1F300}-\\x{1FAFF}]+");
    // 画质标记(词边界;DVDRip 的 DV 无边界不误伤):同集多版本(DV/SDR 双压包)择优用
    private static final Pattern DV_MARK = Pattern.compile("(?i)\\b(?:dv|dovi|dolby\\s*vision)\\b|杜比视界");
    private static final Pattern HDR_MARK = Pattern.compile("(?i)\\bhdr(?:10)?\\b");
    private static final Pattern SDR_MARK = Pattern.compile("(?i)\\bsdr\\b");

    /**
     * 画质标记 → 播放器兼容性惩罚:DV=2(杜比视界 Profile 5 单层 IHLP,不支持的设备解码整屏泛绿/灰)
     * > HDR=1(SDR 屏放偏灰但可看)> 无标记/SDR=0。
     * <p>
     * 标记常在版本目录名而非文件名(线上「悬案」主源:`Season 1（HQ.DV.60fps）`14 集 + `Season 1（SDR.50fps）`17 集,
     * 两个季文件夹,文件名不带标记),故从文件名起**逐段向外**扫描,取最近一段带标记的目录判定;
     * 显式 SDR 段即终答(不再受外层目录噪声影响);同段 DV&SDR 混标(双压包根目录名)区分不到文件级,跳过继续向外。
     */
    public static int picturePenalty(String path) {
        if (path == null || path.isEmpty()) {
            return 0;
        }
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i];
            boolean dv = DV_MARK.matcher(segment).find();
            boolean sdr = SDR_MARK.matcher(segment).find();
            if (dv && sdr) {
                continue;
            }
            if (dv) {
                return 2;
            }
            if (HDR_MARK.matcher(segment).find()) {
                return 1;
            }
            if (sdr) {
                return 0;
            }
        }
        return 0;
    }

    public static boolean isChineseChar(int c) {
        return c >= 0x4E00 && c <= 0x9FA5;
    }

    /** 解析名称中的元数据 id 标记(如 `一念永恒-完结季 [dbid-37448094]` → dbid=37448094)。
     * @param key dbid / tmdbid / bgmid;@return 对应数字 id 字符串,未命中返回 null */
    public static String parseMetaIdTag(String text, String key) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = META_ID_TAG.matcher(text);
        while (matcher.find()) {
            if (matcher.group(1).equalsIgnoreCase(key)) {
                return matcher.group(2);
            }
        }
        return null;
    }

    /** 剥离全部元数据 id 标记(名称匹配前清洗,防标记污染搜索词)。 */
    public static String stripMetaIdTags(String text) {
        return text == null ? null : META_ID_TAG.matcher(text).replaceAll(" ");
    }

    public static boolean isChineseChar2(int c) {
        return (c >= 0x4E00 && c <= 0x9FA5) || (c >= '0' && c <= '9') || c == '：';
    }

    public static boolean isEnglishChar(int c) {
        return (c >= 'A' && c <= 'Z');
    }

    public static boolean isAlphabetic(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == ' ';
    }

    public static boolean isChinese(String text) {
        return text.codePoints().allMatch(TextUtils::isChineseChar2);
    }

    public static boolean isEnglish(String text) {
        return text.codePoints().allMatch(TextUtils::isAlphabetic);
    }

    public static boolean isNumber(String text) {
        return NUMBER1.matcher(text).matches();
    }

    // 剥离行首装饰性前缀（emoji、间隔号、不可见变体选择符等），供调用方在 fixName 之前使用，
    // 避免 fixName 把这些符号转成空格后仍残留前缀。例如 "·✅✅✅【万米危机】" -> "【万米危机】"。
    public static String stripLeadingNoise(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return LEADING_NOISE.matcher(name).replaceFirst("");
    }

    // ---------- 季号解析（追剧订阅与候选标题共用） ----------

    /** 标题级季标记：中文"第N季"、SxxEyy 的季、独立 Sxx、Season N */
    private static final Pattern TITLE_SEASON_CN = Pattern.compile("第\\s*([0-9一二三四五六七八九十]{1,3})\\s*季");
    private static final Pattern TITLE_SEASON_SXXE = Pattern.compile("[Ss](\\d{1,2})\\s*[Ee]\\d{1,3}");
    private static final Pattern TITLE_SEASON_ALONE = Pattern.compile("(?:^|[^A-Za-z0-9])[Ss](\\d{1,2})(?![\\dEe])");
    private static final Pattern TITLE_SEASON_EN = Pattern.compile("(?i)season\\s*(\\d{1,2})");
    /** 剧名尾部的季号后缀，用于还原裸剧名 */
    private static final Pattern TRAILING_SEASON = Pattern.compile(
            "(?i)\\s*(第\\s*[0-9一二三四五六七八九十]{1,3}\\s*季|season\\s*\\d{1,2}|[Ss]\\d{1,2})\\s*$");

    /** 标题级季标记解析：返回标题明确标注的季号；无标记或多个不同季号（跨季合集）返回 null 不参与判定。 */
    public static Integer parseTitleSeason(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        Set<Integer> seasons = new TreeSet<>();
        Matcher cn = TITLE_SEASON_CN.matcher(title);
        while (cn.find()) {
            int value = cn.group(1).matches("\\d+") ? Integer.parseInt(cn.group(1)) : parseChineseNumber(cn.group(1));
            if (value > 0) {
                seasons.add(value);
            }
        }
        collectSeason(title, seasons, TITLE_SEASON_SXXE);
        collectSeason(title, seasons, TITLE_SEASON_ALONE);
        collectSeason(title, seasons, TITLE_SEASON_EN);
        return seasons.size() == 1 ? seasons.iterator().next() : null;
    }

    private static void collectSeason(String title, Set<Integer> seasons, Pattern pattern) {
        Matcher matcher = pattern.matcher(title);
        while (matcher.find()) {
            seasons.add(Integer.parseInt(matcher.group(1)));
        }
    }

    /** 中文数字（一~九十九）转阿拉伯；不可解析返回 0。 */
    public static int parseChineseNumber(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int result = 0;
        int current = 0;
        for (char c : text.toCharArray()) {
            int digit = "零一二三四五六七八九".indexOf(c);
            if (digit >= 0) {
                current = digit;
            } else if (c == '十') {
                result += current == 0 ? 10 : current * 10;
                current = 0;
            } else {
                return 0;
            }
        }
        return result + current;
    }

    /**
     * 剥掉剧名尾部的季号后缀，还原裸剧名（"诛仙 第四季" → "诛仙"）。
     * <p>
     * 订阅名常带季号（片单条目名原样带入），而资源标题的季号写法五花八门（第四季/第4季/S04/4）。
     * 拿带季号的全名做包含匹配等于要求写法逐字相同，会把绝大多数候选误判为不相关。
     * 剥完为空时原样返回——空串会退化成"匹配一切"。
     */
    public static String stripSeasonSuffix(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String stripped = TRAILING_SEASON.matcher(name.trim()).replaceFirst("").trim();
        return stripped.isEmpty() ? name.trim() : stripped;
    }

    /** 整串就是一个季标记（"第四季"/"S04"/"Season 3"），不含任何剧名信息——不可作为匹配名。 */
    public static boolean isBareSeasonMarker(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return TRAILING_SEASON.matcher(text.trim()).replaceFirst("").trim().isEmpty();
    }

    /**
     * 季号兜底：调用方未指定或给的是默认值 1，而剧名明确写了季号时，以剧名为准。
     * <p>
     * 季号错误会同时静默击穿三条链路——候选入池的季过滤、{@code SxxEyy} 文件名的集号识别、
     * 播放列表合并的集号解析——每一条都表现为"什么都没搜到/一集都没有"，极难定位。
     * 用户显式指定的非默认值不覆盖（不猜测用户意图）。
     */
    public static Integer resolveSeason(Integer requested, String name) {
        if (requested != null && requested > 1) {
            return requested;
        }
        Integer parsed = parseTitleSeason(name);
        return parsed != null ? parsed : requested;
    }

    public static String fixName(String name) {
        int index = name.lastIndexOf('/');
        String newName = name.trim();
        if (index > -1) {
            newName = newName.substring(index + 1);
        }

        if (newName.endsWith(")")) {
            index = newName.lastIndexOf('(');
            if (index > 0) {
                newName = newName.substring(0, index);
            }
        }

        if (newName.endsWith("）")) {
            index = newName.lastIndexOf('（');
            if (index > 0) {
                newName = newName.substring(0, index);
            }
        }

        int start = newName.indexOf('《');
        if (start > -1) {
            int end = newName.indexOf('》', index + 1);
            if (end > start) {
                newName = newName.substring(start + 1, end);
            }
        }

        String[] parts = newName.replace("[", "").replace("]", " ").split("[. ]+");
        if (parts.length > 4 && (parts[0].length() > 1 && parts[1].length() > 1 && isChinese(parts[0]))) {
            if (isEnglish(parts[1])) {
                newName = parts[0];
            } else if (parts[1].trim().isEmpty() && isEnglish(parts[2])) {
                newName = parts[0];
            }
        }

        // 新增：移除流媒体平台标签
        newName = STREAMING_PLATFORM.matcher(newName).replaceAll(" ");

        // 新增：移除发布组和字幕组标签
        newName = RELEASE_GROUP.matcher(newName).replaceAll(" ");

        // 新增：处理 S01E01, 1x01 格式
        Matcher seMatcher = SEASON_EPISODE.matcher(newName);
        if (seMatcher.find()) {
            String season = seMatcher.group(1);
            String newNum = number2text(season.substring(1));
            newName = newName.replace(seMatcher.group(0), " 第" + newNum + "季");
        } else {
            seMatcher = SEASON_EPISODE2.matcher(newName);
            if (seMatcher.find()) {
                String season = seMatcher.group(1);
                String newNum = number2text(season);
                newName = newName.replace(seMatcher.group(0), " 第" + newNum + "季");
            }
        }

        // 新增：移除编码格式标签
        newName = AV_FORMAT.matcher(newName).replaceAll(" ");

        // 新增：移除音频格式标签
        newName = AUDIO_FORMAT.matcher(newName).replaceAll(" ");

        // 新增：移除分辨率标签
        newName = RESOLUTION.matcher(newName).replaceAll(" ");

        // Keep the release year available to the caller, but remove episode/update
        // status from the title body before the generic cleanup below.
        newName = TRAILING_EPISODE_STATUS.matcher(newName).replaceFirst("");

        newName = newName
                .replaceAll("第\\d+-\\d+集", " ")
                .replaceAll("1~\\d{1,2}", " ")
                .replaceAll("1-\\d+集", " ")
                .replaceAll("共\\d+集\\+\\d+部剧场版", " ")
                .replaceAll("豆瓣：[0-9.]+", " ")
                .replaceAll("[\\[{](?:dbid|tmdbid|bgmid)-\\d+[\\]}]", " ")
                .replace("天翼网盘", " ")
                .replace("(臻彩)", " ")
                .replace("（真彩）", " ")
                .replace("超前完结", " ")
                .replace("番外", " ")
                .replace("彩蛋", " ")
                .replace("豆瓣", " ")
                .replace("完整全集", " ")
                .replace("稀有国日双语版", " ")
                .replace("官方中英双字", " ")
                .replace("(CC版)", " ")
                .replace("国粤英多音轨", " ")
                .replace("粤语音轨", " ")
                .replace("国英双语", " ")
                .replace("国语配音版", " ")
                .replace("韩语中字", " ")
                .replace("韩语官中", " ")
                .replace("字幕版", " ")
                .replace("国配简繁特效", " ")
                .replace("简繁双语特效字幕", " ")
                .replace("简繁英多国字幕", " ")
                .replace("导评简繁六字幕", " ")
                .replace("内封简英双字", " ")
                .replace("内封简、繁中字", " ")
                .replace("国粤英3语", " ")
                .replace("国粤语配音", " ")
                .replace("粤语配音", " ")
                .replace("中英双语字幕", " ")
                .replace("带中文字幕", " ")
                .replace("日英四语", " ")
                .replace("国日双语", " ")
                .replace("官方中字", " ")
                .replace("台日双语", " ")
                .replace("华语配音", " ")
                .replace("普通话版", " ")
                .replace("（普通话）", " ")
                .replace("外挂双语", " ")
                .replace("内封中字", " ")
                .replace("有字幕", " ")
                .replace("双语版", " ")
                .replace("日语版", " ")
                .replace("电视版本", " ")
                .replace("电视版", " ")
                .replace("外挂中文字幕", " ")
                .replace("内封多字幕", " ")
                .replace("中英特效字幕", " ")
                .replace("简繁英双语字幕", " ")
                .replace("简繁英特效字幕", " ")
                .replace("简繁英双语特效字幕", " ")
                .replace("简繁英字幕", " ")
                .replace("简体字幕", " ")
                .replace("繁英字幕", " ")
                .replace("简繁字幕", " ")
                .replace("繁英字幕", " ")
                .replace("简英字幕", " ")
                .replace("简繁双语字幕", " ")
                .replace("国语音轨", " ")
                .replace("国语配音", " ")
                .replace("国韩多音轨", " ")
                .replace("国英多音轨", " ")
                .replace("多音轨", " ")
                .replace("(粤语中字)", " ")
                .replace("英语中字", " ")
                .replace("BD中英双字", " ")
                .replace("特效中英双字", " ")
                .replace("中英双字", " ")
                .replace("中英字幕", " ")
                .replace("特效字幕", " ")
                .replace("中文字幕", " ")
                .replace("日语无字", " ")
                .replace("国日英三语", " ")
                .replace("日粤英三语", " ")
                .replace("简日双语内封", " ")
                .replace("陆台粤日语", " ")
                .replace("简繁日内封", " ")
                .replace("粤日中字", " ")
                .replace("台配繁中", " ")
                .replace("简中内封", " ")
                .replace("简中内嵌", " ")
                .replace("简体内嵌", " ")
                .replace("简繁内嵌", " ")
                .replace("简繁内嵌", " ")
                .replace("简繁内封", " ")
                .replace("无字幕", " ")
                .replace("双语", " ")
                .replace("【国剧】", " ")
                .replace("国语版", " ")
                .replace("国语", " ")
                .replace("国英", " ")
                .replace("中配", " ")
                .replace("官中", " ")
                .replace("粤语", " ")
                .replace("国粤", " ")
                .replace("剧情", " ")
                .replace("仅英轨", " ")
                .replace("配音版", " ")
                .replace("台配国语", " ")
                .replace("台配", " ")
                .replace("俄语", " ")
                .replace(".中日双语", " ")
                .replace(".日语", " ")
                .replace("日语", " ")
                .replace("日语版", " ")
                .replace("全系列电影", " ")
                .replace("【电影】", " ")
                .replace("系列合集", " ")
                .replace("大合集", " ")
                .replace("(合集）", " ")
                .replace("合集", " ")
                .replace("-系列", " ")
                .replace("系列", " ")
                .replace("持续更新中", " ")
                .replace("更新中", " ")
                .replace(".内嵌", " ")
                .replace(".日配", " ")
                .replace("(客串)", " ")
                .replace("HD720P", " ")
                .replace("720P", " ")
                .replace(".720p", " ")
                .replace(".720P", " ")
                .replace("HD720P", " ")
                .replace("720P", " ")
                .replace(".1080p", " ")
                .replace(".1080P", " ")
                .replace("HD1080P", " ")
                .replace("1080P高码", " ")
                .replace("1080p", " ")
                .replace("1080P", " ")
                .replace(".2160p", " ")
                .replace("2160p", " ")
                .replace("2160P", " ")
                .replace("3840x2160", " ")
                .replace("120帧率版本", " ")
                .replace("60FPS修复珍藏版", " ")
                .replace("60帧率版本", " ")
                .replace("音轨版", " ")
                .replace("HDR60fps", " ")
                .replace("HDR版本", " ")
                .replace("[HDR]", " ")
                .replace("HDR", " ")
                .replace("MP4", " ")
                .replace(".4k", " ")
                .replace(" 4k ", " ")
                .replace("高码4K", " ")
                .replace("4K修复版", " ")
                .replace("4K修复", " ")
                .replace("蓝光原盘REMUX", " ")
                .replace("4K臻彩MAX", " ")
                .replace("4K原盘REMUX", " ")
                .replace("4K REMUX", " ")
                .replace("杜比全景声", " ")
                .replace("杜比视界", " ")
                .replace("杜比", " ")
                .replace("REMUX", " ")
                .replace("REMXU", " ")
                .replace("RMVB", " ")
                .replace("4K HDR", " ")
                .replace("4K版", " ")
                .replace("纯净版", " ")
                .replace("10bit", " ")
                .replace("60fps", " ")
                .replace("WEB-DL", " ")
                .replace("BD", " ")
                .replace("DDP5", " ")
                .replace("BluRay", " ")
                .replace("H265", " ")
                .replace("H264", " ")
                .replace("x265", " ")
                .replace("X264", " ")
                .replace("x264", " ")
                .replace("4K修复珍藏版", " ")
                .replace("蓝光原盘", " ")
                .replace("蓝光高清", " ")
                .replace("蓝光版", " ")
                .replace("蓝光", " ")
                .replace("高码版", " ")
                .replace("部分高清", " ")
                .replace("标清", " ")
                .replace("收藏画质", " ")
                .replace("4K原盘", " ")
                .replace("超清4K修复", " ")
                .replace("超清", " ")
                .replace("4K修复版", " ")
                .replace("4K收藏版", " ")
                .replace("4K双版本", " ")
                .replace("最终剪辑版", " ")
                .replace("双版本", " ")
                .replace("[4K]", " ")
                .replace("4K", " ")
                .replace("4k", " ")
                .replace("60帧", " ")
                .replace("高码率", " ")
                .replace(".超高码率", " ")
                .replace("杜比视界版本", " ")
                .replace("IMAX", " ")
                .replace("+外传", " ")
                .replace("+番外篇", " ")
                .replace("+番外", " ")
                .replace("+漫画", " ")
                .replace("+电影", " ")
                .replace("国漫-", " ")
                .replace("电视剧", " ")
                .replace("剧版", " ")
                .replace("网剧", " ")
                .replace("短剧", " ")
                .replace("衍生剧", " ")
                .replace("美漫", " ")
                .replace("全季", " ")
                .replace("加剧场版", " ")
                .replace("+剧场版", " ")
                .replace("剧场版", " ")
                .replace("加外传", " ")
                .replace("+真人版", " ")
                .replace("真人版", " ")
                .replace("精编版", " ")
                .replace("电视系列片", " ")
                .replace("纪录片专场", " ")
                .replace("真实人物改编", " ")
                .replace("真实故事", " ")
                .replace("迷你剧", " ")
                .replace("系列片", " ")
                .replace("动漫加真人", " ")
                .replace("动漫+真人", " ")
                .replace("导演剪辑版", " ")
                .replace("高码收藏版", " ")
                .replace("高码", " ")
                .replace("高清黑金珍藏版", " ")
                .replace("高清修复版", " ")
                .replace("重置版", " ")
                .replace("洗版", " ")
                .replace("特典映像", " ")
                .replace("收藏版", " ")
                .replace("「珍藏版」", " ")
                .replace("珍藏版", " ")
                .replace("极致版", " ")
                .replace("典藏版", " ")
                .replace("特别版", " ")
                .replace("老版", " ")
                .replace("经典老剧", " ")
                .replace("经典剧", " ")
                .replace("连续剧", " ")
                .replace("未删减版", " ")
                .replace("未删减", " ")
                .replace("无删减", " ")
                .replace("无台标", " ")
                .replace("重制版", " ")
                .replace("完整高清", " ")
                .replace("完结篇", " ")
                .replace("完结", " ")
                .replace("高分剧", " ")
                .replace("未精校", " ")
                .replace("霸王龙压制", " ")
                .replace("酷漫字幕组", " ")
                .replace("凤凰天使", " ")
                .replace("[一只鱼4kyu.cc]", " ")
                .replace("（流媒体）", " ")
                .replace("+Q版", " ")
                .replace("+OVA", " ")
                .replace("+SP", " ")
                .replace("+前传", " ")
                .replace("中国大陆区", " ")
                //.replace("大陆", " ")
                .replace("未分级重剪加长版", " ")
                .replace("【美剧】", " ")
                .replace("【法国】", " ")
                .replace("【西班牙】", " ")
                .replace("【俄罗斯】", " ")
                .replace("【英剧】", " ")
                .replace("【爱情片】", " ")
                .replace("【纪录片】", " ")
                .replace("泰国奇幻剧", " ")
                .replace("【美漫】", " ")
                .replace("欧美剧", " ")
                .replace("美剧", " ")
                .replace("日韩剧", " ")
                .replace("日剧", " ")
                .replace("韩剧", " ")
                .replace("喜剧", " ")
                .replace("综艺", " ")
                .replace("意大利", " ")
                .replace("恐怖剧", " ")
                .replace("科幻剧", " ")
                .replace("国产剧", " ")
                .replace("悬疑|传记剧", " ")
                .replace("悬疑", " ")
                .replace("[恐怖]", " ")
                .replace("惊悚", " ")
                .replace("短片", " ")
                .replace("电影版", " ")
                .replace("-系列", " ")
                .replace("系列", " ")
                .replace("全集", " ")
                .replace("中字", " ")
                .replace("外挂字幕", " ")
                .replace("无字", " ")
                .replace("无水印版", " ")
                .replace("无水印", " ")
                .replace("腾讯水印", " ")
                .replace("腾讯", " ")
                .replace("B站", " ")
                .replace("OVA", " ")
                .replace("TV加MOV", " ")
                .replace("HDTV", " ")
                .replace("GOTV", " ")
                .replace("NHK", " ")
                .replace("人人影视制作", " ")
                .replace("高清翡翠台", " ")
                .replace("TVB版", " ")
                .replace("TVB", " ")
                .replace("ATV", " ")
                .replace("BBC", " ")
                .replace("(剧版)", " ")
                .replace("DVD版", " ")
                .replace("DVD", " ")
                .replace("《单片》", " ")
                .replace("【库库光影】", " ")
                .replace("夸克网盘", " ")
                .replace("UC网盘", " ")
                .replace("迅雷网盘", " ")
                .replace("原盘", " ")
                .replace("·ali", " ")
                .replace("公众号：锦技社", " ")
                .replace("推荐!", " ")
                .replace("[", " ")
                .replace("]", " ")
                .replace("【", " ")
                .replace("】", " ")
                .replace("（", "(")
                .replace("）", ")")
                .replace("《", " ")
                .replace("》", " ")
                .replace(",", " ")
                .replace("..", " ")
                .replace("_", " ")
                .replace("⭐", " ")
                .replace("|", " ")
                .replace("丨", "")
                .replace("+", " ")
                .replace("Ⅰ", "第一季")
                .replace("Ⅱ", "第二季")
                .replace("II", "第二季")
                .replace("III", "第三季")
                .replace("Ⅲ", "第三季")
                .replace("Ⅳ", "第四季")
                .replace("Ⅴ", "第五季")
                .replace("Ⅵ", "第六季")
                .replace("~", "");

        Matcher m = NAME1.matcher(newName);
        if (m.matches()) {
            newName = m.group(1);
        }

        m = NAME2.matcher(newName);
        if (m.matches()) {
            newName = m.group(1);
        }

        m = NAME3.matcher(newName);
        if (m.matches()) {
            newName = m.group(1);
        }

        newName = newName
                .replaceAll("(No.\\d+ ?)", " ")
                .replaceAll("\\d+、", " ")
                .replaceAll("\\.\\d{4}", " ")
                .replaceAll(" \\d{4}", " ")
                .replaceAll("\\s*全\\d+集", " ")
                .replaceAll("第?\\d-\\d+([季部])", " ")
                .replaceAll(".([季部])全", " ")
                .replaceAll("[0-9.]+GB", " ")
                .replaceAll("豆瓣评分：?[0-9.]+", " ")
                .replaceAll("豆瓣\\d\\.\\d", " ")
                .replaceAll("NO \\d+\\｜", " ")
                .replaceAll("\\(\\d{4}\\)", " ")
        ;

        m = NUMBER.matcher(newName);
        if (m.find()) {
            String text = m.group(1);
            if (m.start() > 1 && newName.charAt(m.start() - 1) != ' ') {
                newName = newName.replace("第" + text + "季", " 第" + text + "季");
            }
            String newNum = number2text(text);
            newName = newName.replace(text, newNum);
        } else {
            m = NUMBER2.matcher(newName);
            if (m.find()) {
                String text = m.group(1);
                if (m.start() > 0 && newName.charAt(m.start() - 1) != ' ') {
                    newName = newName.replace(text, " " + text);
                }
            } else {
                m = NUMBER3.matcher(newName);
                if (m.find()) {
                    String text = m.group(1);
                    String newNum = number2text(text.substring(1));
                    newName = newName.replace(text, " 第" + newNum + "季");
                }
            }
        }

        m = NUMBER4.matcher(newName);
        if (m.find()) {
            String text = m.group(1);
            newName = newName.replace(text, "");
        } else {
            m = NUMBER5.matcher(newName);
            if (m.find()) {
                String text = m.group(1);
                newName = newName.replace(text, "");
            } else {
                m = NUMBER6.matcher(newName);
                if (m.find()) {
                    String text = m.group(1);
                    newName = newName.replace(text, "");
                }
            }
        }

        index = newName.indexOf('.');
        if (index > 0 && newName.substring(0, index).codePoints().allMatch(Character::isDigit)) {
            newName = newName.substring(index + 1);
        }

//        if (newName.endsWith(".")) {
//            newName = newName.substring(0, newName.length() - 1);
//        }

        newName = newName
                .replaceAll("\\s+", " ")
                .replaceAll("\\.", " ")
                .trim();
        if (!name.equals(newName)) {
            log.debug("name: {} -> {}", name, newName);
        }
        return newName;
    }

    public static String cleanMediaTitle(String name) {
        if (name == null) {
            return null;
        }
        Matcher yearMatcher = RELEASE_YEAR.matcher(name);
        String year = yearMatcher.find() ? yearMatcher.group(1) : null;
        String mediaName = stripLeadingNoise(name).trim();
        Matcher metadataMatcher = TRAILING_BRACKET_METADATA.matcher(mediaName);
        if (metadataMatcher.find() && metadataMatcher.start() > 0) {
            mediaName = mediaName.substring(0, metadataMatcher.start()).trim();
        }
        int aliasSeparator = firstAliasSeparator(mediaName);
        if (aliasSeparator > 0) {
            String primaryTitle = mediaName.substring(0, aliasSeparator).trim();
            String alternateTitle = mediaName.substring(aliasSeparator + 1).trim();
            if (HAN.matcher(primaryTitle).find() && HAN.matcher(alternateTitle).find()) {
                mediaName = primaryTitle;
            }
        }
        String title = collapseCjkSpaces(fixName(mediaName));
        if (title.isBlank() || year == null) {
            return title;
        }
        return title + "(" + year + ")";
    }

    private static int firstAliasSeparator(String name) {
        int slash = name.indexOf('/');
        int fullWidthSlash = name.indexOf('／');
        if (slash < 0) {
            return fullWidthSlash;
        }
        if (fullWidthSlash < 0) {
            return slash;
        }
        return Math.min(slash, fullWidthSlash);
    }

    // Uploaders often dot-separate CJK characters to evade detection ("百.花.杀").
    // fixName turns those dots into spaces, so the key becomes "百 花 杀" and no longer
    // matches the real title "百花杀" stored without spaces. Collapse whitespace between
    // Han characters so such names resolve. Applied deliberately, not inside fixName:
    // legitimate names like "重紫 第10季" rely on the separating space.
    public static String collapseCjkSpaces(String name) {
        if (name == null) {
            return null;
        }
        return name.replaceAll("(?<=[\\u4e00-\\u9fa5])\\s+(?=[\\u4e00-\\u9fa5])", "");
    }

    public static String number2text(String text) {
        if (text.startsWith("0") && text.length() > 1) {
            text = text.substring(1);
        }
        if (text.isEmpty()) {
            return text;
        }
        int num = Integer.parseInt(text);
        String newNum;
        if (num <= 10) {
            newNum = NUMBERS.get(num);
        } else if (num < 20) {
            newNum = "十" + NUMBERS.get(num % 10);
        } else if (num % 10 == 0) {
            newNum = NUMBERS.get(num / 10) + "十";
        } else {
            newNum = NUMBERS.get(num / 10) + "十" + NUMBERS.get(num % 10);
        }
        return newNum;
    }

    public static String updateName(String name) {
        int n = name.length();
        if (n > 1 && (TextUtils.isEnglishChar(name.codePointAt(0)) && TextUtils.isChineseChar(name.codePointAt(1)))) {
            name = name.substring(1);
            n = name.length();

        }

        if (n > 2) {
            if (TextUtils.isEnglishChar(name.codePointAt(0))
                    && (name.charAt(1) == ' ' || name.charAt(1) == '.')
                    && TextUtils.isChineseChar(name.codePointAt(2))) {
                name = name.substring(2);
                n = name.length();
            }

            if (name.charAt(n - 1) == '1' && TextUtils.isChineseChar(name.codePointAt(n - 2))) {
                name = name.substring(0, n - 1);
            }
        }

        if (name.endsWith(" 第一季")) {
            name = name.substring(0, name.length() - 4);
        }

        int start = name.indexOf('.');
        if (start == 4) {
            try {
                Integer.parseInt(name.substring(0, 4));
                int end = name.indexOf('.', start + 1);
                if (end > start + 1) {
                    name = name.substring(start + 1, end);
                }
            } catch (Exception e) {
                // ignore
            }
        }
        return name;
    }

    public static String truncate(CharSequence charSequence, int threshold) {
        return charSequence.length() > threshold ? charSequence.subSequence(0, threshold) + "..." : charSequence.toString();
    }

    public static int minDistance(String sourceStr, String targetStr) {
        int sourceLen = sourceStr.length();
        int targetLen = targetStr.length();

        if (sourceLen == 0) {
            return targetLen;
        }
        if (targetLen == 0) {
            return sourceLen;
        }

        int[][] arr = new int[sourceLen + 1][targetLen + 1];

        for (int i = 0; i < sourceLen + 1; i++) {
            arr[i][0] = i;
        }

        for (int j = 0; j < targetLen + 1; j++) {
            arr[0][j] = j;
        }

        char sourceChar;
        char targetChar;

        for (int i = 1; i < sourceLen + 1; i++) {
            sourceChar = sourceStr.charAt(i - 1);
            for (int j = 1; j < targetLen + 1; j++) {
                targetChar = targetStr.charAt(j - 1);
                if (sourceChar == targetChar) {
                    arr[i][j] = arr[i - 1][j - 1];
                } else {
                    arr[i][j] = (Math.min(Math.min(arr[i - 1][j], arr[i][j - 1]), arr[i - 1][j - 1])) + 1;
                }
            }
        }

        return arr[sourceLen][targetLen];
    }

    public static boolean isNormal(String name) {
        int n = name.length();
        if (name.toLowerCase().startsWith("season ")) {
            return false;
        }
        if (name.contains("花絮")) {
            return false;
        }
        if (name.contains("彩蛋")) {
            return false;
        }
        if (name.equals("高画质版")) {
            return false;
        }
        if (name.equals("国配")) {
            return false;
        }
        if (name.equals("高码")) {
            return false;
        }
        if (name.equals("SDR")) {
            return false;
        }
        if (name.equals("A-Z")) {
            return false;
        }
        if (name.equals("02")) {
            return false;
        }
        if (name.equals("11 - à Zélie")) {
            return false;
        }
        if (name.equals("CO(rrespondance) VID(éo) #9 - à Zélie")) {
            return false;
        }
        if (name.equals("CO(rrespondance) VID(éo) #11 - à Zélie")) {
            return false;
        }
        if (name.equals("字幕")) {
            return false;
        }
        if (name.equals("真人秀")) {
            return false;
        }
        if (name.equals("曲艺辙痕")) {
            return false;
        }
        if (name.equals("字幕勿扰")) {
            return false;
        }
        if (name.equals("新奇的整理")) {
            return false;
        }
        if (name.equals("读·豆瓣")) {
            return false;
        }
        if (name.equals("每个人都有他自己的电影")) {
            return false;
        }
        if (name.equals("SP")) {
            return false;
        }
        if (name.equals("TV")) {
            return false;
        }
        if (name.equals("OAD")) {
            return false;
        }
        if (name.equals("actors")) {
            return false;
        }
        if (name.equals("动漫")) {
            return false;
        }
        if (name.equals("特别篇")) {
            return false;
        }
        if (name.equals("中国")) {
            return false;
        }
        if (name.equals("韩国")) {
            return false;
        }
        if (name.equals("意大利")) {
            return false;
        }
        if (name.equals("澳大利亚")) {
            return false;
        }
        if (name.equals("非洲")) {
            return false;
        }
        if (name.equals("新建文件夹")) {
            return false;
        }
        if (name.equals("Specials")) {
            return false;
        }
        if (name.equals("TV字幕")) {
            return false;
        }
        if (name.equals("剧场版")) {
            return false;
        }
        if (name.equals("大合集")) {
            return false;
        }
        if (name.equals("蓝光电影")) {
            return false;
        }
        if (name.equals("电影版")) {
            return false;
        }
        if (name.equalsIgnoreCase("movie")) {
            return false;
        }
        if (name.equalsIgnoreCase("OST")) {
            return false;
        }
        if (name.equalsIgnoreCase("2160p")) {
            return false;
        }
        if (name.equals("番外")) {
            return false;
        }
        if (name.equals("国语版")) {
            return false;
        }
        if (name.codePoints().allMatch(Character::isDigit)) {
            return false;
        }
        if (name.startsWith("S") && name.substring(1).codePoints().allMatch(Character::isDigit)) {
            return false;
        }
        if (name.toUpperCase().startsWith("1080P")) {
            return false;
        }
        if (name.toUpperCase().startsWith("4K")) {
            return false;
        }
        if (name.endsWith("版本")) {
            return false;
        }
        if (name.endsWith("语版")) {
            return false;
        }
        if (name.endsWith(" 番外")) {
            return false;
        }
        if (name.endsWith(" 大电影")) {
            return false;
        }
        if (NUMBER1.matcher(name).matches()) {
            return false;
        }
        if (n == 1 && TextUtils.isEnglishChar(name.charAt(0))) {
            return false;
        }
        return true;
    }

}
