package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.tg.Message;
import cn.har01d.alist_tvbox.dto.tg.SearchRequest;
import cn.har01d.alist_tvbox.entity.TelegramChannel;
import cn.har01d.alist_tvbox.entity.TelegramChannelRepository;
import cn.har01d.alist_tvbox.service.MediaSubscriptionService;
import cn.har01d.alist_tvbox.service.SubscriptionService;
import cn.har01d.alist_tvbox.service.TelegramService;
import cn.har01d.alist_tvbox.tvbox.Category;
import cn.har01d.alist_tvbox.tvbox.CategoryList;
import cn.har01d.alist_tvbox.tvbox.MovieList;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Slf4j
@RestController
public class TelegramController {
    private final TelegramChannelRepository telegramChannelRepository;
    private final TelegramService telegramService;
    private final SubscriptionService subscriptionService;
    private final MediaSubscriptionService mediaSubscriptionService;
    private final ObjectMapper objectMapper;

    public TelegramController(TelegramChannelRepository telegramChannelRepository,
                              TelegramService telegramService,
                              SubscriptionService subscriptionService,
                              MediaSubscriptionService mediaSubscriptionService,
                              ObjectMapper objectMapper) {
        this.telegramChannelRepository = telegramChannelRepository;
        this.telegramService = telegramService;
        this.subscriptionService = subscriptionService;
        this.mediaSubscriptionService = mediaSubscriptionService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/telegram/search")
    public List<Message> searchByKeyword(String wd) {
        return telegramService.search(wd, 100, false, false);
    }

    @GetMapping("/api/telegram/tg-search/health")
    public ObjectNode tgSearchHealth() {
        return telegramService.getTgSearchHealth();
    }

    @GetMapping("/tg-search")
    public Object browse(String id, String t, String ac, String wd, String title, boolean web, @RequestParam(required = false, defaultValue = "1") int pg) throws IOException {
        return browse("", id, t, ac, wd, title, web, pg);
    }

    @GetMapping("/tg-search/{token}")
    public Object browse(@PathVariable String token, String id, String t, String ac, String wd, String title, boolean web, @RequestParam(required = false, defaultValue = "1") int pg) throws IOException {
        subscriptionService.checkToken(token);
        int uid = mediaSubscriptionService.resolveUid(token);
        // 旧"我的追更"详情通道(msub:{id})已下线:TVBox 入口收敛到 csp_Media(/media/{token})
//        if (StringUtils.isNotBlank(id) && id.startsWith(MediaSubscriptionService.VOD_ID_PREFIX)) {
//            return mediaSubscriptionDetail(token, id, ac, title);
//        }
        if (StringUtils.isNotBlank(id)) {
            Object result = telegramService.detail(id, ac, title, wd);
            // 一键订阅入口(§10.1):TG 条目详情页追加"追更"操作组,spider 拦截 $msub$/$munsub$ 前缀
//            if (result instanceof MovieList movieList && !movieList.getList().isEmpty()) {
//                mediaSubscriptionService.appendFollowTrack(movieList.getList().get(0), uid, id, title);
//            }
            return result;
        } else if (StringUtils.isNotBlank(t)) {
            // 旧 t=msub 列表通道已下线:TVBox 入口收敛到 csp_Media(/media/{token})
//            if (t.equals(MediaSubscriptionService.CATEGORY_ID)) {
//                return mediaSubscriptionService.contentList(mediaSubscriptionService.resolveUid(token));
//            }
            if (t.equals("0")) {
                return telegramService.searchMovies("", web, 5);
            }
            return telegramService.list(t, web, pg);
        } else if (StringUtils.isNotBlank(wd)) {
            return telegramService.searchMovies(wd, web, 20);
        }
        Object category = telegramService.category(web);
        // 首页分类首位插入"我的追更"(仅有订阅时显示)
//        if (category instanceof CategoryList categoryList && categoryList.getCategories() != null
//                && !mediaSubscriptionService.contentList(uid).getList().isEmpty()) {
//            Category item = new Category();
//            item.setType_id(MediaSubscriptionService.CATEGORY_ID);
//            item.setType_name("我的追更");
//            categoryList.getCategories().add(0, item);
//            categoryList.setTotal(categoryList.getCategories().size());
//            categoryList.setLimit(categoryList.getTotal());
//        }
        return category;
    }

    // 旧 TVBox 操作组回调端点($msub$/$munsub$ 拦截的落点)已下线:注入取消后不再触发,入口收敛到 csp_Media
//    /** TVBox 操作组动作:action = follow/unfollow/next,token 即鉴权(同 live-follow)。 */
//    @PostMapping("/tg-search/{token}/msub/{action}")
//    public Map<String, Object> mediaSubscriptionAction(@PathVariable String token, @PathVariable String action,
//                                                       @RequestBody Map<String, Object> body) {
//        subscriptionService.checkToken(token);
//        int uid = mediaSubscriptionService.resolveUid(token);
//        return mediaSubscriptionService.handleAction(uid, action, body);
//    }

    /** 追剧订阅详情(msub:{id}):播放列表复用 TvBoxService,固定挂载路径保证续看不因换源断链。 */
    private Object mediaSubscriptionDetail(String token, String id, String ac, String title) {
        String vid = id.substring(MediaSubscriptionService.VOD_ID_PREFIX.length());
        int subscriptionId;
        try {
            subscriptionId = Integer.parseInt(vid.split("\\$")[0].split("#")[0]);
        } catch (NumberFormatException e) {
            return telegramService.detail(id, ac, title, null);
        }
        int uid = mediaSubscriptionService.resolveUid(token);
        return mediaSubscriptionService.contentDetail(uid, subscriptionId, ac, title);
    }

    @GetMapping("/tgsc")
    public Object browseTgSearch(String id, String t, String ac, String wd, String title, @RequestParam(required = false, defaultValue = "1") int pg, @RequestParam(required = false, defaultValue = "30") int size) {
        return browseTgSearch("", id, t, ac, wd, title, pg, size);
    }

    @GetMapping("/tgsc/{token}")
    public Object browseTgSearch(@PathVariable String token, String id, String t, String ac, String wd, String title, @RequestParam(required = false, defaultValue = "1") int pg, @RequestParam(required = false, defaultValue = "30") int size) {
        subscriptionService.checkToken(token);
        if (StringUtils.isNotBlank(id)) {
            return telegramService.detail(id, ac, title, wd);
        } else if (StringUtils.isNotBlank(t)) {
            if (t.equals("0")) {
                return telegramService.searchTgSearchMovies("", pg, 120);
            }
            return telegramService.listTgSearch(t, pg, size);
        } else if (StringUtils.isNotBlank(wd)) {
            return telegramService.searchTgSearchMovies(wd, pg, size);
        }
        return telegramService.categoryTgSearch();
    }

    @GetMapping("/tg-db")
    public Object db(String id, String t, String ac, String wd, String title, String sort, Integer year, String genre, String region, @RequestParam(required = false, defaultValue = "1") int pg, @RequestParam(required = false, defaultValue = "30") int size) throws IOException {
        return db("", id, t, ac, wd, title, sort, year, genre, region, pg, size);
    }

    @GetMapping("/tg-db/{token}")
    public Object db(@PathVariable String token, String id, String t, String ac, String wd, String title, String sort, Integer year, String genre, String region, @RequestParam(required = false, defaultValue = "1") int pg, @RequestParam(required = false, defaultValue = "30") int size) throws IOException {
        subscriptionService.checkToken(token);
        if (StringUtils.isNotBlank(id)) {
            return telegramService.detail(id, ac, title, wd);
        } else if (StringUtils.isNotBlank(t)) {
            if (t.equals("0")) {
                t = "suggestion";
            }
            return telegramService.listDouban(t, ac, sort, year, genre, region, pg, size);
        } else if (StringUtils.isNotBlank(wd)) {
            return telegramService.searchDouban(wd, 20);
        }
        return telegramService.categoryDouban();
    }

    @GetMapping("/tgsz")
    public Map<String, Object> searchZx(String keyword, String channelUsername, HttpServletResponse response) {
        response.setHeader("server", "hypercorn-h11");
        return telegramService.searchZx(keyword, channelUsername);
    }

    @GetMapping("/tgs")
    public String searchPg(String keyword, String channelUsername, String encode, HttpServletResponse response) {
        response.setHeader("server", "hypercorn-h11");
        return telegramService.searchPg(keyword, channelUsername, encode);
    }

    @PostMapping("/tgs")
    public String searchPgPost(@RequestBody String body, HttpServletResponse response) throws JsonProcessingException {
        String json = new String(Base64.getDecoder().decode(body));
        log.debug("searchPgPost: {} {}", body, json);
        SearchRequest request = objectMapper.readValue(json, SearchRequest.class);
        response.setHeader("server", "hypercorn-h11");
        if ("2".equals(request.getPage())) {
            return "";
        }
        return telegramService.searchPg(request.getKeyword(), request.getChannelUsername(), request.getEncode());
    }

    @GetMapping(value = "/tgs/s/{id}", produces = "text/plain;charset=UTF-8")
    public String searchWeb(@PathVariable String id, String keyword, String encode, HttpServletResponse response) {
        response.setHeader("server", "hypercorn-h11");
        return telegramService.searchWeb(keyword, id, encode);
    }

    @PostMapping(value = "/tgs/s/{id}", produces = "text/plain;charset=UTF-8")
    public String searchWebPost(@PathVariable String id, @RequestBody String body, HttpServletResponse response) throws JsonProcessingException {
        String json = new String(Base64.getDecoder().decode(body));
        SearchRequest request = objectMapper.readValue(json, SearchRequest.class);
        response.setHeader("server", "hypercorn-h11");
        if ("2".equals(request.getPage())) {
            return "";
        }
        return telegramService.searchWeb(request.getKeyword(), request.getChannelUsername(), request.getEncode());
    }

    @GetMapping("/api/telegram/channels")
    public List<TelegramChannel> list() {
        return telegramService.list();
    }

    @PostMapping("/api/telegram/resolveUsername")
    public TelegramChannel create(@RequestBody TelegramChannel channel) {
        return telegramService.create(channel);
    }

    @PostMapping("/api/telegram/channels")
    public TelegramChannel save(@RequestBody TelegramChannel channel) {
        return telegramChannelRepository.save(channel);
    }

    @PutMapping("/api/telegram/channels")
    public List<TelegramChannel> updateAll(@RequestBody List<TelegramChannel> channels) {
        return telegramService.updateAll(channels);
    }

    @DeleteMapping("/api/telegram/channels/{id}")
    public void delete(@PathVariable Long id) {
        telegramChannelRepository.deleteById(id);
    }

    @PostMapping("/api/telegram/reloadChannels")
    public List<TelegramChannel> reloadChannels() throws IOException {
        return telegramService.reloadChannels();
    }

    @PostMapping("/api/telegram/validateChannels")
    public List<TelegramChannel> validateChannels() {
        return telegramService.validateChannels();
    }

}
