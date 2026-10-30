package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.FilterDto;
import cn.har01d.alist_tvbox.dto.LiveFollowDto;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.live.web.LiveController;
import cn.har01d.alist_tvbox.service.PluginWorkerService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4(type 4)适配层:TVBox 客户端不下 jar,home/category/detail/search/player 五动作全部
 * 以 HTTP 参数打到 /t4/{token}/v/{siteKey},本控制器逐参数复刻 spring.jar 各 csp_* spider
 * 的回源调用(CatVodTVSpider 对应类),业务仍落在既有端点。T4=控制面中转:壳零运行时依赖,
 * 元数据/鉴权/站点请求全由服务端代劳,PC 应用/网页壳/老盒子/Node 壳均可接。
 *
 * 配置入口(三形态):
 *  /t4/{t4token}           独立地址(推荐,与订阅无关;T4 专用 token 见 SubscriptionService)
 *  /t4/{token}/{id}        旧订阅形态(vod token + 订阅号;兼容保留,上游 type3 站点本就全删)
 *  /t4/{id}                旧裸订阅形态(兼容保留)
 * T4 接口总开关 t4_enabled 关闭后全部 404;代理策略 t4_proxy_mode=all(默认)/direct 见下。
 * 仅独立地址 t4_standalone_only 开启后两个旧形态(含其五动作/CENC 面)全部 404,
 * T4 只对 /t4/{t4token} 开放——token 发给谁谁能用,其余用户只能 /sub。
 *
 * FongMi 客户端契约(SiteApi):
 *  home   GET api?filter=true(filter 标志忽略,home 恒带分类+筛选+推荐 list)
 *  分类   GET api?ac=detail&t={tid}&pg={页}&ext={base64UrlSafe(JSON筛选值)}
 *  详情   GET api?ac=detail&ids={id}
 *  搜索   GET api?wd={词}&quick={bool}&pg={页}(pg&gt;1 才带)
 *  播放   GET api?play={id}&flag={线路}(flag 忽略,线路由 id 自描述)
 * 响应为 spider Result JSON(与 type 3 spider 返回同构)。
 *
 * TG/盘搜族详情依赖条目标题兜底(spring.jar 端为爬虫 titleCache):本层以响应旁路捕获
 * list 条目的 vod_id→vod_name 与最近搜索词,详情请求自动补带 title/wd。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
public class T4Controller {
    /** 需要标题缓存兜底的站点(= 持有 titleCache 的 spider)。 */
    private static final Set<String> TITLE_SITES =
            Set.of("csp_TgDouBan", "csp_TgChannel", "csp_TgWeb", "csp_FishPanSou", "csp_FishPanSouGroup");

    private final SubscriptionService subscriptionService;
    private final AppProperties appProperties;
    private final TvBoxController tvBoxController;
    private final PianDanController pianDanController;
    private final MediaLibraryController mediaLibraryController;
    private final BiliBiliController biliBiliController;
    private final EmbyController embyController;
    private final JellyfinController jellyfinController;
    private final FeiniuController feiniuController;
    private final LiveController liveController;
    private final TelegramController telegramController;
    private final RemoteSearchController remoteSearchController;
    private final PlayController playController;
    private final PluginWorkerService pluginWorkerService;
    private final PluginRepository pluginRepository;
    private final ObjectMapper objectMapper;

    private final Cache<String, String> titleCache =
            Caffeine.newBuilder().maximumSize(5000).expireAfterAccess(Duration.ofHours(2)).build();
    private final Cache<String, String> keywordCache =
            Caffeine.newBuilder().maximumSize(200).expireAfterAccess(Duration.ofHours(1)).build();

    // 单段路径双语义消歧:T4 专用 token 命中 → 独立 T4 配置(与订阅无关,新唯一推荐入口);
    // 未命中 → 旧裸订阅形态(兼容保留)。T4 专用 token 独立于 vod token 体系,泄露只废 T4 一个面。
    @GetMapping("/t4/{x}")
    public Map<String, Object> config(@PathVariable String x) {
        guardT4Enabled();
        if (subscriptionService.isT4Token(x)) {
            // 先映射回背后 vod token 再过 checkToken,建立 currentToken/租户上下文
            subscriptionService.checkToken(subscriptionService.resolveT4Token(x));
            return subscriptionService.standaloneT4(x, pluginWorkerService.isAvailable());
        }
        guardT4StandaloneOnly(x);
        return subscriptionService.subscriptionT4("", x, pluginWorkerService.isAvailable());
    }

    @GetMapping("/t4/{token}/{id}")
    public Map<String, Object> config(@PathVariable String token, @PathVariable String id) {
        guardT4Enabled();
        guardT4StandaloneOnly(token);
        subscriptionService.checkToken(token);
        return subscriptionService.subscriptionT4(token, id, pluginWorkerService.isAvailable());
    }

    /** T4 接口总开关(t4_enabled):关闭后配置/五动作/CENC 桥全部 404。 */
    private void guardT4Enabled() {
        if (!appProperties.isT4Enabled()) {
            throw new NotFoundException();
        }
    }

    /**
     * 仅独立地址模式(t4_standalone_only):旧订阅形态一律 404——任何持有 vod token/
     * 订阅号的用户都不再能借 /t4/{token}/{id}、/t4/{id} 及其五动作/CENC 面使用 T4,
     * 只剩 /t4/{t4_token} 独立地址(token 只发给允许用 T4 的少数人),其余用户只能 /sub。
     */
    private void guardT4StandaloneOnly(String rawToken) {
        if (appProperties.isT4StandaloneOnly() && !subscriptionService.isT4Token(rawToken)) {
            throw new NotFoundException();
        }
    }

    /** T4 B站播放身份:全代理=t4(服务端代理地址形态);直连优先=t4d(原始 durl+脱敏头)。 */
    private String t4BiliClient() {
        return "direct".equals(appProperties.getT4ProxyMode()) ? "t4d" : "t4";
    }

    /** T4 直播详情 client:全代理=web(服务端代理地址形态);直连优先=null(裸直链/dual 双线路)。 */
    private String t4LiveClient() {
        return "direct".equals(appProperties.getT4ProxyMode()) ? null : "web";
    }

    // FongMi type4 恒 GET(catvod 三参 newCall=拼 query);@RequestMapping 兼容杂牌壳子的
    // 表单 POST(Spring 表单参数自动并入绑定);JSON POST 形态见下方 body 兜底
    @RequestMapping("/t4/{token}/v/{site}")
    public Object vod(@PathVariable String token, @PathVariable String site,
                      String t, String ids, String wd, String quick, String play, String ext, Integer pg,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        guardT4Enabled();
        String pathToken = token;
        // 仅独立地址模式:旧形态配置(vod token/裸订阅段)生成的 api 也不再可达
        guardT4StandaloneOnly(pathToken);
        // T4 专用 token 命中则换算回背后 vod token(插件 payload/播放均按 vod token 口径);
        // pathToken 原样保留,用于生成 CENC 桥等对客户端暴露的地址(T4 面不嵌 vod token)
        token = subscriptionService.resolveT4Token(token);
        subscriptionService.checkToken(token);
        // URL 路径段不带 csp_ 前缀(applyT4 生成口径);带前缀的旧形态兼容
        String siteKey = site.startsWith("csp_") ? site : "csp_" + site;
        if (!SubscriptionService.T4_SITE_KEYS.contains(siteKey)) {
            // 插件源(服务端 Python 运行时,PluginWorkerService);运行时不可用则 404。
            // T4 不可见插件(订阅源管理「T4」开关)同样 404:配置不出现,直连五动作也不可达
            Plugin plugin = findPluginBySiteKey(site);
            if (plugin == null || !plugin.isT4Visible() || !pluginWorkerService.isAvailable()) {
                throw new NotFoundException();
            }
            return pluginVod(token, pathToken, plugin, site, t, ids, wd, quick, play, ext, pg);
        }
        // JSON POST 形态(部分壳子):查询/表单参数全缺位时从 JSON body 取参
        if (StringUtils.isAllBlank(t, ids, wd, play, ext) && pg == null) {
            String contentType = request.getContentType();
            if (contentType != null && contentType.contains("json")) {
                Map<String, String> body = readJsonBody(request);
                t = body.get("t");
                ids = body.get("ids");
                wd = body.get("wd");
                quick = body.get("quick");
                play = body.get("play");
                ext = body.get("ext");
                String page = body.get("pg");
                pg = page == null ? null : parseInt(page);
            }
        }
        log.info("t4 site: {}  t: {}  ids: {}  wd: {}  play: {}  page: {}", siteKey, t, ids, wd, play, pg);
        boolean quickSearch = Boolean.parseBoolean(quick);
        Object result;
        if (StringUtils.isNotBlank(play)) {
            result = player(token, siteKey, play, request);
        } else if (StringUtils.isNotBlank(ids)) {
            result = detail(token, siteKey, ids, request, response);
        } else if (StringUtils.isNotBlank(wd)) {
            result = search(token, siteKey, wd, quickSearch, pg, request, response);
        } else if (StringUtils.isNotBlank(t)) {
            result = category(token, siteKey, t, pg, ext, request, response);
        } else {
            result = home(token, siteKey, request, response);
        }
        return captureTitles(siteKey, result);
    }

    /** JSON POST body → 参数表;解析失败按空处理(回落 home)。 */
    private Map<String, String> readJsonBody(HttpServletRequest request) {
        try (var reader = request.getReader()) {
            JsonNode node = objectMapper.readTree(reader);
            Map<String, String> result = new LinkedHashMap<>();
            node.fieldNames().forEachRemaining(name -> {
                JsonNode value = node.get(name);
                if (value != null && value.isValueNode()) {
                    result.put(name, value.asText());
                }
            });
            return result;
        } catch (Exception e) {
            log.debug("t4 json body parse failed", e);
            return Map.of();
        }
    }

    /** 插件源(服务端 Python 运行时)五动作:extend 服务端自建,worker 返回原样透传。 */
    private Object pluginVod(String token, String pathToken, Plugin plugin, String site,
                             String t, String ids, String wd, String quick, String play, String ext, Integer pg)
            throws IOException {
        String extend = t4PluginExtend(token, plugin);
        Map<String, Object> params = new HashMap<>();
        String method;
        if (StringUtils.isNotBlank(play)) {
            method = "play";
            params.put("play", play);
            // 红果密文流等需服务端解密的场景:worker 把 #hg-cenc= URL 改写为此桥下地址
            // (嵌 pathToken:T4 专用 token 形态下桥地址仍是 T4 面,不泄漏 vod token)
            params.put("proxy_base", subscriptionService.readHostAddress("/t4cenc/" + pathToken + "/" + site));
        } else if (StringUtils.isNotBlank(ids)) {
            method = "detail";
            params.put("ids", ids);
        } else if (StringUtils.isNotBlank(wd)) {
            method = "search";
            params.put("wd", wd);
            params.put("quick", Boolean.parseBoolean(quick));
        } else if (StringUtils.isNotBlank(t)) {
            method = "category";
            params.put("t", t);
            params.put("pg", String.valueOf(pg == null ? 1 : pg));
            Map<String, String> filters = decodeExt(ext);
            if (!filters.isEmpty()) {
                params.put("extend", filters);
            }
        } else {
            method = "home";
        }
        log.info("t4 plugin: {}  method: {}  t: {}  ids: {}  wd: {}  play: {}", site, method, t, ids, wd, play);
        return pluginWorkerService.call(site, extend, method, params);
    }

    // T4 CENC 解密桥:转发到插件 worker 内的 127.0.0.1 解密代理(Range 透传,seek 可用)
    @RequestMapping("/t4cenc/{token}/{site}/**")
    public void cenc(@PathVariable String token, @PathVariable String site,
                     HttpServletRequest request, HttpServletResponse response) throws IOException {
        guardT4Enabled();
        // URI 定位用原始路径段(T4 专用 token 形态下 resolved 与之不同);鉴权按换算后的 vod token。
        // 仅独立地址模式:旧形态配置生成的桥地址同样不再可达
        String pathToken = token;
        guardT4StandaloneOnly(pathToken);
        token = subscriptionService.resolveT4Token(token);
        subscriptionService.checkToken(token);
        String uri = request.getRequestURI();
        int start = uri.indexOf("/t4cenc/" + pathToken + "/" + site + "/");
        if (start < 0) {
            throw new NotFoundException();
        }
        String path = uri.substring(start + ("/t4cenc/" + pathToken + "/" + site).length());
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String query = request.getQueryString();
        // 查找键与 pluginVod 注册 worker 的键严格同源:URL 路径段原样(裸 hex 或上游
        // csp_ 前缀 key 均为插件 key 本身)。插件 key 无 csp_ 寻址约定,勿再补前缀——
        // 补了查空必报「插件代理服务未运行」(线上 #t4cenc 404 形态)。
        pluginWorkerService.forward(site, "/" + path + (query == null ? "" : "?" + query), request, response);
    }

    /** 服务端 worker 的插件 extend:复用 buildPluginExtPayload,剔除设备端专属字段。 */
    private String t4PluginExtend(String token, Plugin plugin) throws IOException {
        String base = subscriptionService.readHostAddress("");
        Map<String, Object> payload = SubscriptionService.buildPluginExtPayload(plugin, base,
                token, token, "", false, null);
        // local_proxy_config 置空:Atvp._play 不带 client-proxy(T4 客户端无本地代理);
        // preheatUrl 是设备端预热清单,worker 无此缓存目录。
        // t4_play_type=T4 播放身份:t4(全代理)=直连带 Cookie 的夸克/UC 强制 /p 且 Cookie/multiUrls
        // 不下发;t4-direct(直连优先策略)=无凭证屏障盘直链直出省带宽(TvBoxService 判定)
        payload.remove("local_proxy_config");
        payload.remove("preheatUrl");
        payload.put("t4_play_type", "direct".equals(appProperties.getT4ProxyMode()) ? "t4-direct" : "t4");
        return java.util.Base64.getEncoder()
                .encodeToString(objectMapper.writeValueAsString(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 配置站点 key → 插件(pluginSiteKey 反查:externalId 优先,回落 plugin-{id})。 */
    private Plugin findPluginBySiteKey(String key) {
        var byExternal = pluginRepository.findByExternalId(key);
        if (byExternal.isPresent()) {
            return byExternal.get();
        }
        if (key.startsWith("plugin-")) {
            try {
                return pluginRepository.findById(Integer.parseInt(key.substring(7))).orElse(null);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** home = 分类列表 + homeVideo 推荐位合并(spring.jar 端两次调用,FongMi type 3 合并同构)。 */
    private Object home(String token, String site, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Object home = homeBody(token, site, request, response);
        Object homeVideo = homeVideoBody(token, site, request, response);
        return mergeHomeVideo(home, homeVideo);
    }

    private Object homeBody(String token, String site, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        return switch (site) {
            case "csp_AList" -> tvBoxController.api(token, null, null, null, null, null, null, 1, 100, 1, 0, request);
            case "csp_XiaoYa" -> tvBoxController.api(token, null, null, null, null, null, null, 1, 100, 0, 0, request);
            case "csp_PianDan" -> pianDanController.browse(token, null, null, null, 1, 20, Map.of());
            case "csp_Media" ->
                    mediaLibraryController.browse(token, null, null, null, null, null, 1, Map.of(), request);
            case "csp_BiliBili" ->
                    biliBiliController.api(token, null, null, null, false, new FilterDto(), null, 1, request, response);
            case "csp_Emby" -> embyController.browse(token, null, null, null, null, 1);
            case "csp_Jellyfin" -> jellyfinController.browse(token, null, null, null, null, 1);
            case "csp_FeiNiu" -> feiniuController.browse(token, null, null, null, null, 1, request);
            case "csp_Live" -> liveController.browse(token, null, null, null, null, null, null, 1);
            case "csp_TgDouBan" -> telegramController.db(token, null, null, null, null, null, null, null, null, null, 1, 30);
            case "csp_TgChannel" -> telegramController.browseTgSearch(token, null, null, null, null, null, 1, 30);
            case "csp_TgWeb" -> telegramController.browse(token, null, null, null, null, null, true, 1);
            case "csp_FishPanSou" -> remoteSearchController.pansou(token, null, null, null, null, 1);
            case "csp_FishPanSouGroup" -> remoteSearchController.pansouGroup(token, null, null, null, null, 1);
            default -> throw new NotFoundException();
        };
    }

    private Object homeVideoBody(String token, String site, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        return switch (site) {
            case "csp_AList" -> tvBoxController.api(token, null, null, "recommend", null, null, null, 1, 100, 1, 0, request);
            case "csp_XiaoYa" -> tvBoxController.api(token, null, null, "recommend", null, null, null, 1, 100, 0, 0, request);
            case "csp_PianDan" -> pianDanController.browse(token, "0", null, null, 1, 20, Map.of());
            case "csp_Media" -> mediaLibraryController.browse(token, null, "0", null, null, null, 1, Map.of(), request);
            case "csp_BiliBili" ->
                    biliBiliController.api(token, null, "recommend", null, false, new FilterDto(), null, 1, request, response);
            case "csp_Emby" -> embyController.browse(token, "recommend", null, null, null, 1);
            case "csp_Jellyfin" -> jellyfinController.browse(token, "recommend", null, null, null, 1);
            case "csp_FeiNiu" -> feiniuController.browse(token, "recommend", null, null, null, 1, request);
            case "csp_Live" -> liveController.browse(token, "recommend", null, null, null, null, null, 1);
            case "csp_TgDouBan" -> telegramController.db(token, null, "0", null, null, null, null, null, null, null, 1, 30);
            case "csp_TgChannel" -> telegramController.browseTgSearch(token, null, "0", null, null, null, 1, 30);
            case "csp_TgWeb" -> telegramController.browse(token, null, "0", null, null, null, true, 1);
            case "csp_FishPanSou" -> remoteSearchController.pansou(token, null, "0", null, null, 1);
            default -> null;
        };
    }

    private Object category(String token, String site, String t, Integer pg, String ext,
                            HttpServletRequest request, HttpServletResponse response) throws IOException {
        int page = pg == null ? 1 : pg;
        Map<String, String> extend = decodeExt(ext);
        return switch (site) {
            case "csp_AList" -> tvBoxController.api(token, t, toJson(extend), null, null, null, null, page, 100, 1, 0, request);
            case "csp_XiaoYa" -> tvBoxController.api(token, t, toJson(extend), null, null, null, null, page, 100, 0, 0, request);
            case "csp_PianDan" -> pianDanController.browse(token, t, null, null, page, 20, extend);
            case "csp_Media" -> mediaLibraryController.browse(token, null, t, null, null, null, page, extend, request);
            case "csp_BiliBili" ->
                    biliBiliController.api(token, t, null, null, false, toFilter(extend), null, page, request, response);
            case "csp_Emby" -> embyController.browse(token, null, null, t, null, page);
            case "csp_Jellyfin" -> jellyfinController.browse(token, null, null, t, null, page);
            case "csp_FeiNiu" -> feiniuController.browse(token, null, null, t, null, page, request);
            case "csp_Live" -> liveController.browse(token, null, null, null, t, extend.get("platform"), null, page);
            case "csp_TgDouBan" -> telegramController.db(token, null, t, null, null, null, extend.get("sort"),
                    parseInt(extend.get("year")), extend.get("genre"), extend.get("region"), page, 30);
            case "csp_TgChannel" -> telegramController.browseTgSearch(token, null, t, null, null, null, page, 30);
            case "csp_TgWeb" -> telegramController.browse(token, null, t, null, null, null, true, page);
            case "csp_FishPanSou" -> remoteSearchController.pansou(token, null, t, null, null, page);
            case "csp_FishPanSouGroup" -> remoteSearchController.pansouGroup(token, null, t, null, null, page);
            default -> throw new NotFoundException();
        };
    }

    private Object detail(String token, String site, String ids, HttpServletRequest request,
                          HttpServletResponse response) throws IOException {
        if (TITLE_SITES.contains(site)) {
            String title = titleCache.getIfPresent(cacheKey(site, ids));
            String keyword = keywordCache.getIfPresent(cacheKey(token, site));
            return switch (site) {
                case "csp_TgDouBan" ->
                        telegramController.db(token, ids, null, null, null, title, null, null, null, null, 1, 30);
                case "csp_TgChannel" ->
                        telegramController.browseTgSearch(token, ids, null, null, null, title, 1, 30);
                case "csp_TgWeb" ->
                        telegramController.browse(token, ids, null, null, null, title, true, 1);
                case "csp_FishPanSou" ->
                        remoteSearchController.pansou(token, ids, null, null, title, 1);
                case "csp_FishPanSouGroup" ->
                        remoteSearchController.pansouGroup(token, ids, null, null, title, 1);
                default -> throw new NotFoundException();
            };
        }
        return switch (site) {
            case "csp_AList" -> tvBoxController.api(token, null, null, ids, null, null, null, 1, 100, 1, 0, request);
            case "csp_XiaoYa" -> tvBoxController.api(token, null, null, ids, null, null, null, 1, 100, 0, 0, request);
            // 片单导航:条目 vod_id(db:/tmdb:/s:/douban:)与 /media 片单 id 同族,委托其片单详情
            // = 服务端代搜(豆瓣元数据+按片名搜电报资源网格+msubadd 动作)。T3 靠客户端 indexs=1
            // 全局搜片,但 indexs 仅 FongMi 遵守(docs/t4-sidecar-borrow-assessment.md A3),
            // 通用壳子须服务端代劳;非片单形态 id 维持空详情
            case "csp_PianDan" -> isPianDanNavId(ids)
                    ? mediaLibraryController.browse(token, ids, null, null, null, null, 1, Map.of(), request)
                    : Map.of();
            case "csp_Media" -> mediaLibraryController.browse(token, ids, null, null, null, null, 1, Map.of(), request);
            case "csp_BiliBili" ->
                    biliBiliController.api(token, null, ids, null, false, new FilterDto(), null, 1, request, response);
            case "csp_Emby" -> embyController.browse(token, ids, null, null, null, 1);
            case "csp_Jellyfin" -> jellyfinController.browse(token, ids, null, null, null, 1);
            case "csp_FeiNiu" -> feiniuController.browse(token, ids, null, null, null, 1, request);
            case "csp_Live" ->
                    // platform 参位在 ids 分支语义为 client:web = 服务端代理地址形态
                    // (Twitch/SOOP 仅 web 代理、酷狗/Inke/看看/YY web 走纯代理模式),
                    // T4 壳子无本地代理/带头能力,与网页端同口径;直连优先策略(t4_proxy_mode=direct)
                    // 下传 null:Twitch/SOOP 裸直链、酷狗/Inke/看看/YY 走 dual 双线路
                    // (直连优先+代理兜底),虎牙恒 /p 不受影响
                    liveController.browse(token, ids, null, null, null, t4LiveClient(), null, 1);
            default -> throw new NotFoundException();
        };
    }

    private Object search(String token, String site, String wd, boolean quick, Integer pg,
                          HttpServletRequest request, HttpServletResponse response) throws IOException {
        int page = pg == null ? 1 : pg;
        if (TITLE_SITES.contains(site)) {
            keywordCache.put(cacheKey(token, site), wd);
        }
        return switch (site) {
            case "csp_AList" -> tvBoxController.api(token, null, null, null, null, wd, null, page, 100, 1, 0, request);
            case "csp_XiaoYa" -> tvBoxController.api(token, null, null, null, null, wd, null, page, 100, 0, 0, request);
            case "csp_PianDan" -> pianDanController.browse(token, null, null, wd, page, 20, Map.of());
            case "csp_Media" -> mediaLibraryController.browse(token, null, null, null, wd, null, page, Map.of(), request);
            case "csp_BiliBili" ->
                    biliBiliController.api(token, null, null, wd, quick, new FilterDto(), null, 1, request, response);
            case "csp_Emby" -> embyController.browse(token, null, wd, null, null, 1);
            case "csp_Jellyfin" -> jellyfinController.browse(token, null, wd, null, null, 1);
            case "csp_FeiNiu" -> feiniuController.browse(token, null, wd, null, null, 1, request);
            case "csp_Live" -> liveController.browse(token, null, wd, null, null, null, null, 1);
            case "csp_TgDouBan" -> telegramController.db(token, null, null, null, wd, null, null, null, null, null, 1, 30);
            case "csp_TgChannel" -> telegramController.browseTgSearch(token, null, null, null, wd, null, page, 30);
            case "csp_TgWeb" -> telegramController.browse(token, null, null, null, wd, null, true, 1);
            case "csp_FishPanSou" -> remoteSearchController.pansou(token, null, null, wd, null, 1);
            case "csp_FishPanSouGroup" -> remoteSearchController.pansouGroup(token, null, null, wd, null, 1);
            default -> throw new NotFoundException();
        };
    }

    private Object player(String token, String site, String play, HttpServletRequest request) throws IOException {
        return switch (site) {
            case "csp_AList", "csp_XiaoYa", "csp_Media", "csp_TgDouBan", "csp_TgChannel", "csp_TgWeb",
                 "csp_FishPanSou", "csp_FishPanSouGroup" ->
                    playController.play(token, null, null, play, null, null, false, request);
            // B站条目 id 走 bvid 参位;动作前缀(bili-like- 等)由 /play 的 bvid→id 兜底归位。
            // X-CLIENT 标记 t4/t4d:BiliBiliService 据此出服务端代理地址形态(t4)或
            // 直连形态(t4d,原始 durl+脱敏头,由 t4_proxy_mode 直连优先策略触发)
            case "csp_BiliBili" ->
                    playController.play(token, null, null, null, play, null, false, withT4Client(request, t4BiliClient()));
            case "csp_Emby" -> embyController.play(token, play, 0L, request);
            case "csp_Jellyfin" -> jellyfinController.play(token, play, 0L, request);
            case "csp_FeiNiu" -> feiniuController.play(token, play, 0L, request);
            case "csp_Live" -> livePlay(token, play, request);
            default -> throw new NotFoundException();
        };
    }

    /** B站播放请求以 t4/t4d 身份下发(X-CLIENT 头,同 spring.jar 各 spider 报包名的口径)。 */
    private static HttpServletRequest withT4Client(HttpServletRequest request, String client) {
        return new jakarta.servlet.http.HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                return "X-CLIENT".equalsIgnoreCase(name) ? client : super.getHeader(name);
            }

            @Override
            public java.util.Enumeration<String> getHeaders(String name) {
                return "X-CLIENT".equalsIgnoreCase(name)
                        ? java.util.Collections.enumeration(List.of(client))
                        : super.getHeaders(name);
            }
        };
    }

    /** 直播播放:详情 vod_play_url 已是 /p 代理直链,spring.jar Live spider 同样直接透传并补移动端头;
     *  关注轨道动作(follow$/unfollow$平台$房间号)与未开播占位原为设备端拦截,服务端等价复刻。 */
    private Object livePlay(String token, String play, HttpServletRequest request) throws IOException {
        if (play.startsWith("follow$") || play.startsWith("unfollow$")) {
            String[] parts = play.split("\\$");
            if (parts.length < 3) {
                throw new BadRequestException("播放参数格式不正确");
            }
            boolean unfollow = play.startsWith("unfollow$");
            LiveFollowDto dto = new LiveFollowDto();
            dto.setPlatform(parts[1]);
            dto.setRoomId(parts[2]);
            if (unfollow) {
                liveController.unfollow(token, dto);
            } else {
                liveController.follow(token, dto);
            }
            return Map.of("msg", unfollow ? "已取消关注" : "已关注");
        }
        if ("offline".equals(play)) {
            return Map.of("msg", "直播间未开播");
        }
        if (play.startsWith("http")) {
            // 等价 spring.jar Live.play(url):jx/parse=0 直链播放 + 与 spider 相同的移动端头
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("jx", 0);
            result.put("parse", 0);
            result.put("header",
                    "{\"Referer\":\"https://m.huya.com/\",\"User-Agent\":\"Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1\"}");
            result.put("url", play);
            return result;
        }
        return liveController.play(token, play, request);
    }

    /** TG/盘搜族响应旁路捕获 vod_id→vod_name(spring.jar 端 titleCache 的服务端等价)。 */
    private Object captureTitles(String site, Object result) {
        if (!TITLE_SITES.contains(site)) {
            return result;
        }
        List<MovieDetail> list = null;
        if (result instanceof MovieList movieList) {
            list = movieList.getList();
        } else if (result instanceof CategoryList categoryList) {
            list = categoryList.getList();
        }
        if (list != null) {
            for (MovieDetail item : list) {
                if (StringUtils.isNotBlank(item.getVod_id()) && StringUtils.isNotBlank(item.getVod_name())) {
                    titleCache.put(cacheKey(site, item.getVod_id()), item.getVod_name());
                }
            }
        }
        return result;
    }

    private Object mergeHomeVideo(Object home, Object homeVideo) {
        Map<String, Object> base;
        try {
            base = home == null ? new LinkedHashMap<>() : objectMapper.convertValue(home, new TypeReference<>() {});
            if (homeVideo != null) {
                Map<String, Object> part = objectMapper.convertValue(homeVideo, new TypeReference<>() {});
                Object list = part.get("list");
                if (list != null) {
                    base.put("list", list);
                }
            }
        } catch (IllegalArgumentException e) {
            log.debug("t4 home merge skipped: {}", e.getMessage());
            return home;
        }
        base.putIfAbsent("list", List.of());
        return base;
    }

    /** FongMi type 4 分类筛选值:ext=base64UrlSafe(JSON)。异常静默降级为无筛选。 */
    private Map<String, String> decodeExt(String ext) {
        if (StringUtils.isBlank(ext)) {
            return Map.of();
        }
        try {
            byte[] bytes;
            try {
                bytes = Base64.getUrlDecoder().decode(ext);
            } catch (IllegalArgumentException e) {
                bytes = Base64.getDecoder().decode(ext);
            }
            JsonNode node = objectMapper.readTree(bytes);
            Map<String, String> result = new LinkedHashMap<>();
            node.fieldNames().forEachRemaining(name -> {
                JsonNode value = node.get(name);
                if (value != null && value.isValueNode()) {
                    result.put(name, value.asText());
                }
            });
            return result;
        } catch (Exception e) {
            log.debug("t4 ext decode failed", e);
            return Map.of();
        }
    }

    private String toJson(Map<String, String> extend) {
        if (extend.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(extend);
        } catch (Exception e) {
            return null;
        }
    }

    private FilterDto toFilter(Map<String, String> extend) {
        if (extend.isEmpty()) {
            return new FilterDto();
        }
        try {
            return objectMapper.convertValue(extend, FilterDto.class);
        } catch (IllegalArgumentException e) {
            return new FilterDto();
        }
    }

    private static Integer parseInt(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String cacheKey(String a, String b) {
        return a + '\u0000' + b;
    }

    /** 片单导航条目 id 族(与 MediaLibraryController.isPianDanId 同口径)。 */
    private static boolean isPianDanNavId(String id) {
        return id != null && (id.startsWith("db:") || id.startsWith("douban:") || id.startsWith("tmdb:")
                || id.startsWith("s:"));
    }
}
