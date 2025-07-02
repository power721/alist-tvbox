package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.NavigationDto;
import cn.har01d.alist_tvbox.entity.Navigation;
import cn.har01d.alist_tvbox.entity.NavigationRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class NavigationService {
    private final NavigationRepository navigationRepository;

    public NavigationService(NavigationRepository navigationRepository) {
        this.navigationRepository = navigationRepository;
    }

    @PostConstruct
    public void setup() {
        if (navigationRepository.count() == 0) {
            loadBiliBiliCategory();
        } else {
            List<Navigation> list = navigationRepository.findAll();
            addRecommend(list);
            addIndex(list);
            addUps(list);
            addFollow(list);
            addTypes(list);
        }
    }

    private void addRecommend(List<Navigation> list) {
        if (list.stream().filter(e -> "recommend$0".equals(e.getValue())).findAny().isEmpty()) {
            navigationRepository.save(new Navigation("推荐", "recommend$0", 1, true, true, 1));
        }
    }

    private void addIndex(List<Navigation> list) {
        if (list.stream().filter(e -> "index$1".equals(e.getValue())).findAny().isEmpty()) {
            navigationRepository.save(new Navigation("番剧索引", "index$1", 1, true, true, 20));
        }
    }

    private void addUps(List<Navigation> list) {
        if (list.stream().filter(e -> "ups".equals(e.getValue())).findAny().isEmpty()) {
            navigationRepository.save(new Navigation("UP主", "ups", 1, false, true, 21));
        }
    }

    private void addFollow(List<Navigation> list) {
        if (list.stream().filter(e -> "follow$0".equals(e.getValue())).findAny().isEmpty()) {
            navigationRepository.save(new Navigation("我的关注", "follow$0", 1, true, true, 12));
        }
        if (list.stream().filter(e -> "follow:1".equals(e.getValue())).findAny().isEmpty()) {
            navigationRepository.save(new Navigation("我的追番", "follow:1", 1, true, true, 13));
        }
        if (list.stream().filter(e -> "follow:2".equals(e.getValue())).findAny().isEmpty()) {
            navigationRepository.save(new Navigation("我的追剧", "follow:2", 1, true, true, 14));
        }
        if (list.stream().filter(e -> "coin$0".equals(e.getValue())).findAny().isEmpty()) {
            navigationRepository.save(new Navigation("我的投币", "coin$0", 1, true, true, 15));
        }
        if (list.stream().filter(e -> "like$0".equals(e.getValue())).findAny().isEmpty()) {
            navigationRepository.save(new Navigation("我的点赞", "like$0", 1, true, true, 16));
        }
        if (list.stream().filter(e -> "collect$0".equals(e.getValue())).findAny().isEmpty()) {
            navigationRepository.save(new Navigation("我的收藏", "collect$0", 1, true, true, 17));
        }
    }

    private void addTypes(List<Navigation> all) {
        List<NavigationDto> list = list();
        Map<String, NavigationDto> map = new HashMap<>();
        for (NavigationDto item : list) {
            map.put(item.getValue(), item);
        }
        NavigationDto parent = map.get("36");
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("122"))) {
            navigationRepository.save(new Navigation("野生技术协会", "122", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
        parent = map.get("1");
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("257"))) {
            navigationRepository.save(new Navigation("配音", "257", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
        parent = map.get("3");
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("265"))) {
            navigationRepository.save(new Navigation("AI音乐", "265", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("266"))) {
            navigationRepository.save(new Navigation("音乐粉丝饭拍", "266", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("267"))) {
            navigationRepository.save(new Navigation("电台", "267", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
        parent = map.get("223");
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("258"))) {
            navigationRepository.save(new Navigation("汽车知识科普", "258", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("227"))) {
            navigationRepository.save(new Navigation("购车攻略", "227", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("247"))) {
            navigationRepository.save(new Navigation("新能源车", "247", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("248"))) {
            navigationRepository.save(new Navigation("房车", "248", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
        parent = map.get("5");
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("262"))) {
            navigationRepository.save(new Navigation("CP安利", "262", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("263"))) {
            navigationRepository.save(new Navigation("颜值安利", "263", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("264"))) {
            navigationRepository.save(new Navigation("娱乐资讯", "264", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
        parent = map.get("181");
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("256"))) {
            navigationRepository.save(new Navigation("短片", "256", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("260"))) {
            navigationRepository.save(new Navigation("影视整活", "260", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("259"))) {
            navigationRepository.save(new Navigation("AI影像", "259", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("261"))) {
            navigationRepository.save(new Navigation("影视综合", "261", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
        parent = map.get("129");
        if (all.stream().noneMatch(e -> e.getType() == 2 && e.getValue().equals("255"))) {
            navigationRepository.save(new Navigation("颜值·网红舞", "255", 2, true, true, parent.getChildren().size() + 1, parent.getId()));
        }
    }

    @Transactional
    public void loadBiliBiliCategory() {
        List<Navigation> list = new ArrayList<>();
        int order = 10;
        list.add(new Navigation("推荐", "recommend$0", 1, true, true, order++));
        list.add(new Navigation("动态", "feed$0", 1, false, true, order++));
        list.add(new Navigation("我的关注", "follow$0", 1, false, true, order++));
        list.add(new Navigation("收藏夹", "fav$0", 1, false, true, order++));
        list.add(new Navigation("频道", "channel$0", 1, false, true, order++));
        list.add(new Navigation("历史记录", "history$0", 1, false, true, order++));
        list.add(new Navigation("全站热榜", "0", 1, true, true, order++));
        list.add(new Navigation("电影热榜", "season$2", 1, true, true, order++));
        list.add(new Navigation("电视剧热榜", "season$5", 1, true, true, order++));
        list.add(new Navigation("综艺热榜", "season$7", 1, true, true, order++));
        list.add(new Navigation("纪录片热榜", "season$3", 1, true, true, order++));
        list.add(new Navigation("动画热榜", "season$4", 1, true, true, order++));
        list.add(new Navigation("番剧热榜", "season$1", 1, true, true, order++));
        list.add(new Navigation("热门", "pop$1", 1, true, true, order++));
        list.add(new Navigation("国创", "167", 1, true, true, order++)); // 15
        list.add(new Navigation("纪录片", "177", 1, true, true, order++));
        list.add(new Navigation("电影", "23", 1, true, true, order++));
        list.add(new Navigation("电视剧", "11", 1, true, true, order++));
        list.add(new Navigation("科技", "188", 1, true, true, order++));
        list.add(new Navigation("知识", "36", 1, true, true, order++));
        list.add(new Navigation("动画", "1", 1, true, true, order++));
        list.add(new Navigation("番剧", "13", 1, true, true, order++));
        list.add(new Navigation("音乐", "3", 1, true, true, order++));
        list.add(new Navigation("游戏", "4", 1, true, true, order++));
        list.add(new Navigation("娱乐", "5", 1, true, true, order++));
        list.add(new Navigation("影视", "181", 1, true, true, order++));
        list.add(new Navigation("舞蹈", "129", 1, true, true, order++));
        list.add(new Navigation("运动", "234", 1, true, true, order++));
        list.add(new Navigation("汽车", "223", 1, true, true, order++));
        list.add(new Navigation("生活", "160", 1, true, true, order++));
        list.add(new Navigation("美食", "211", 1, true, true, order++));
        list.add(new Navigation("动物圈", "217", 1, true, true, order++));
        list.add(new Navigation("时尚", "155", 1, true, true, order++));
        list.add(new Navigation("鬼畜", "119", 1, true, true, order++));
        list.add(new Navigation("原创", "origin$0", 1, false, true, order++));
        list.add(new Navigation("新人", "rookie$0", 1, false, true, order++));
        list.add(new Navigation("番剧索引", "index$1", 1, true, true, 20));
        list.add(new Navigation("UP主", "ups", 1, false, true, 21));

        int parent = 15; // 国创
        order = 1;
        list.add(new Navigation("国产动画", "153", 2, true, true, order++, parent));
        list.add(new Navigation("国产原创", "168", 2, true, true, order++, parent));
        list.add(new Navigation("布袋戏", "169", 2, true, true, order++, parent));
        list.add(new Navigation("资讯", "170", 2, true, true, order++, parent));
        list.add(new Navigation("动态漫·广播剧 ", "195", 2, true, true, order++, parent));

        parent++; // 纪录片
        order = 1;
        list.add(new Navigation("人文·历史", "37", 2, true, true, order++, parent));
        list.add(new Navigation("科学·探索·自然", "178", 2, true, true, order++, parent));
        list.add(new Navigation("军事", "179", 2, true, true, order++, parent));
        list.add(new Navigation("社会·美食·旅行", "180", 2, true, true, order++, parent));

        parent++; // 电影
        order = 1;
        list.add(new Navigation("华语电影", "147", 2, true, true, order++, parent));
        list.add(new Navigation("欧美电影", "145", 2, true, true, order++, parent));
        list.add(new Navigation("日本电影", "146", 2, true, true, order++, parent));
        list.add(new Navigation("其他国家", "83", 2, true, true, order++, parent));

        parent++; // 电视剧
        order = 1;
        list.add(new Navigation("国产剧", "185", 2, true, true, order++, parent));
        list.add(new Navigation("海外剧", "187", 2, true, true, order++, parent));

        parent++; // 科技
        order = 1;
        list.add(new Navigation("数码", "95", 2, true, true, order++, parent));
        list.add(new Navigation("软件应用", "230", 2, true, true, order++, parent));
        list.add(new Navigation("计算机技术", "231", 2, true, true, order++, parent));
        list.add(new Navigation("极客DIY", "233", 2, true, true, order++, parent));

        parent++; // 知识
        order = 1;
        list.add(new Navigation("科学科普", "201", 2, true, true, order++, parent));
        list.add(new Navigation("社科·法律·心理", "124", 2, true, true, order++, parent));
        list.add(new Navigation("人文历史", "228", 2, true, true, order++, parent));
        list.add(new Navigation("财经商业", "207", 2, true, true, order++, parent));
        list.add(new Navigation("校园学习", "208", 2, true, true, order++, parent));
        list.add(new Navigation("职业职场", "209", 2, true, true, order++, parent));
        list.add(new Navigation("设计·创意", "229", 2, true, true, order++, parent));
        list.add(new Navigation("野生技术协会", "122", 2, true, true, order++, parent));

        parent++; // 动画
        order = 1;
        list.add(new Navigation("MAD·AMV", "24", 2, true, true, order++, parent));
        list.add(new Navigation("MMD·3D", "25", 2, true, true, order++, parent));
        list.add(new Navigation("短片·手书·配音", "47", 2, true, true, order++, parent));
        list.add(new Navigation("手办·模玩", "210", 2, true, true, order++, parent));
        list.add(new Navigation("特摄", "86", 2, true, true, order++, parent));
        list.add(new Navigation("动漫杂谈", "253", 2, true, true, order++, parent));
        list.add(new Navigation("综合", "27", 2, true, true, order++, parent));

        parent++; // 番剧
        order = 1;
        list.add(new Navigation("资讯", "51", 2, true, true, order++, parent));
        list.add(new Navigation("官方延伸", "152", 2, true, true, order++, parent));
        list.add(new Navigation("完结动画", "32", 2, true, true, order++, parent));
        list.add(new Navigation("连载动画", "33", 2, true, true, order++, parent));

        parent++; // 音乐
        order = 1;
        list.add(new Navigation("原创音乐", "28", 2, true, true, order++, parent));
        list.add(new Navigation("翻唱", "31", 2, true, true, order++, parent));
        list.add(new Navigation("VOCALOID·UTAU", "30", 2, true, true, order++, parent));
        list.add(new Navigation("演奏", "59", 2, true, true, order++, parent));
        list.add(new Navigation("MV", "193", 2, true, true, order++, parent));
        list.add(new Navigation("音乐现场", "29", 2, true, true, order++, parent));
        list.add(new Navigation("音乐综合", "130", 2, true, true, order++, parent));
        list.add(new Navigation("乐评盘点", "243", 2, true, true, order++, parent));
        list.add(new Navigation("音乐教学", "244", 2, true, true, order++, parent));

        parent++; // 游戏
        order = 1;
        list.add(new Navigation("单机游戏", "17", 2, true, true, order++, parent));
        list.add(new Navigation("电子竞技", "171", 2, true, true, order++, parent));
        list.add(new Navigation("手机游戏", "172", 2, true, true, order++, parent));
        list.add(new Navigation("网络游戏", "65", 2, true, true, order++, parent));
        list.add(new Navigation("桌游棋牌", "173", 2, true, true, order++, parent));
        list.add(new Navigation("GMV", "121", 2, true, true, order++, parent));
        list.add(new Navigation("音游", "136", 2, true, true, order++, parent));
        list.add(new Navigation("Mugen", "19", 2, true, true, order++, parent));

        parent++; // 娱乐
        order = 1;
        list.add(new Navigation("综艺", "71", 2, true, true, order++, parent));
        list.add(new Navigation("娱乐杂谈", "241", 2, true, true, order++, parent));
        list.add(new Navigation("粉丝创作", "242", 2, true, true, order++, parent));
        list.add(new Navigation("明星综合", "137", 2, true, true, order++, parent));

        parent++; // 影视
        order = 1;
        list.add(new Navigation("影视杂谈", "182", 2, true, true, order++, parent));
        list.add(new Navigation("影视剪辑", "183", 2, true, true, order++, parent));
        list.add(new Navigation("小剧场", "85", 2, true, true, order++, parent));
        list.add(new Navigation("预告·资讯", "184", 2, true, true, order++, parent));

        parent++; // 舞蹈
        order = 1;
        list.add(new Navigation("宅舞", "20", 2, true, true, order++, parent));
        list.add(new Navigation("舞蹈综合", "154", 2, true, true, order++, parent));
        list.add(new Navigation("舞蹈教程", "156", 2, true, true, order++, parent));
        list.add(new Navigation("街舞", "198", 2, true, true, order++, parent));
        list.add(new Navigation("明星舞蹈", "199", 2, true, true, order++, parent));
        list.add(new Navigation("中国舞", "200", 2, true, true, order++, parent));

        parent++; // 运动
        order = 1;
        list.add(new Navigation("篮球", "235", 2, true, true, order++, parent));
        list.add(new Navigation("足球", "249", 2, true, true, order++, parent));
        list.add(new Navigation("健身", "164", 2, true, true, order++, parent));
        list.add(new Navigation("竞技体育", "236", 2, true, true, order++, parent));
        list.add(new Navigation("运动文化", "237", 2, true, true, order++, parent));
        list.add(new Navigation("运动综合", "238", 2, true, true, order++, parent));

        parent++; // 汽车
        order = 1;
        list.add(new Navigation("赛车", "245", 2, true, true, order++, parent));
        list.add(new Navigation("改装玩车", "246", 2, true, true, order++, parent));
        list.add(new Navigation("新能源车", "247", 2, true, true, order++, parent));
        list.add(new Navigation("房车", "248", 2, true, true, order++, parent));
        list.add(new Navigation("摩托车 ", "240", 2, true, true, order++, parent));
        list.add(new Navigation("购车攻略", "227", 2, true, true, order++, parent));
        list.add(new Navigation("汽车生活", "176", 2, true, true, order++, parent));

        parent++; // 生活
        order = 1;
        list.add(new Navigation("搞笑", "138", 2, true, true, order++, parent));
        list.add(new Navigation("出行", "250", 2, true, true, order++, parent));
        list.add(new Navigation("三农", "251", 2, true, true, order++, parent));
        list.add(new Navigation("家居房产", "239", 2, true, true, order++, parent));
        list.add(new Navigation("手工 ", "161", 2, true, true, order++, parent));
        list.add(new Navigation("绘画", "162", 2, true, true, order++, parent));
        list.add(new Navigation("日常", "21", 2, true, true, order++, parent));

        parent++; // 美食
        order = 1;
        list.add(new Navigation("美食制作", "76", 2, true, true, order++, parent));
        list.add(new Navigation("美食侦探", "212", 2, true, true, order++, parent));
        list.add(new Navigation("美食测评", "213", 2, true, true, order++, parent));
        list.add(new Navigation("田园美食", "214", 2, true, true, order++, parent));
        list.add(new Navigation("美食记录 ", "215", 2, true, true, order++, parent));

        parent++; // 动物
        order = 1;
        list.add(new Navigation("喵星人", "218", 2, true, true, order++, parent));
        list.add(new Navigation("汪星人", "219", 2, true, true, order++, parent));
        list.add(new Navigation("大熊猫", "220", 2, true, true, order++, parent));
        list.add(new Navigation("野生动物", "221", 2, true, true, order++, parent));
        list.add(new Navigation("爬宠 ", "222", 2, true, true, order++, parent));
        list.add(new Navigation("动物综合 ", "75", 2, true, true, order++, parent));

        parent++; // 时尚
        order = 1;
        list.add(new Navigation("美妆护肤", "157", 2, true, true, order++, parent));
        list.add(new Navigation("仿妆cos", "252", 2, true, true, order++, parent));
        list.add(new Navigation("穿搭", "158", 2, true, true, order++, parent));
        list.add(new Navigation("时尚潮流", "159", 2, true, true, order++, parent));

        parent++; // 鬼畜
        order = 1;
        list.add(new Navigation("鬼畜调教", "22", 2, true, true, order++, parent));
        list.add(new Navigation("音MAD", "26", 2, true, true, order++, parent));
        list.add(new Navigation("人力VOCALOID", "126", 2, true, true, order++, parent));
        list.add(new Navigation("鬼畜剧场", "216", 2, true, true, order++, parent));
        list.add(new Navigation("教程演示 ", "127", 2, true, true, order++, parent));

        navigationRepository.saveAll(list);
        log.info("load BiliBili category");
    }

    public List<NavigationDto> list() {
        Map<Integer, NavigationDto> map = new HashMap<>();
        List<Navigation> list = navigationRepository.findAll();
        List<NavigationDto> result = new ArrayList<>();
        for (Navigation navigation : list) {
            if (navigation.getType() != 2) {
                NavigationDto item = new NavigationDto(navigation);
                map.put(item.getId(), item);
                result.add(item);
            }
        }

        for (Navigation navigation : list) {
            if (navigation.getType() == 2) {
                NavigationDto item = new NavigationDto(navigation);
                NavigationDto parent = map.get(navigation.getParentId());
                if (parent != null) {
                    parent.getChildren().add(item);
                } else {
                    result.add(item);
                }
            }
        }

        result.sort(Comparator.comparing(NavigationDto::getOrder));
        for (NavigationDto navigation : result) {
            navigation.getChildren().sort(Comparator.comparing(NavigationDto::getOrder));
        }

        return result;
    }

    public void saveAll(List<NavigationDto> dto) {
        Map<Integer, NavigationDto> map = new HashMap<>();
        for (NavigationDto item : dto) {
            map.put(item.getId(), item);
            for (NavigationDto child : item.getChildren()) {
                map.put(child.getId(), child);
            }
        }

        List<Navigation> list = navigationRepository.findAll();
        for (Navigation item : list) {
            NavigationDto updated = map.get(item.getId());
            if (updated != null) {
                item.setOrder(updated.getOrder());
                item.setShow(updated.isShow());
            }
        }
        navigationRepository.saveAll(list);
    }

    private void validate(NavigationDto dto) {
        if (dto.getType() == 2 && dto.getParentId() <= 0) {
            throw new BadRequestException("");
        }
        if (StringUtils.isBlank(dto.getName())) {
            throw new BadRequestException("名称不能为空");
        }
        if (StringUtils.isBlank(dto.getValue())) {
            throw new BadRequestException("值不能为空");
        }
    }

    public Navigation create(NavigationDto dto) {
        validate(dto);
        if (dto.getType() == 2) {
            dto.setOrder(navigationRepository.countByParentId(dto.getParentId()) + 1);
        }
        Navigation navigation = new Navigation();
        syncNavigation(dto, navigation);
        return navigationRepository.save(navigation);
    }

    public Navigation update(Integer id, NavigationDto dto) {
        validate(dto);
        Navigation navigation = navigationRepository.findById(id).orElseThrow(NotFoundException::new);
        navigation.setId(id);
        syncNavigation(dto, navigation);
        return navigationRepository.save(navigation);
    }

    private static void syncNavigation(NavigationDto dto, Navigation navigation) {
        navigation.setName(dto.getName());
        navigation.setValue(dto.getValue());
        navigation.setType(dto.getType());
        navigation.setOrder(dto.getOrder());
        navigation.setShow(dto.isShow());
        navigation.setReserved(dto.isReserved());
        navigation.setParentId(dto.getParentId());
    }

    public void delete(Integer id) {
        navigationRepository.deleteById(id);
    }
}
