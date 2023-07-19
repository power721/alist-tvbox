package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.NavigationDto;
import cn.har01d.alist_tvbox.entity.Navigation;
import cn.har01d.alist_tvbox.entity.NavigationRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
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
            List<Navigation> list = new ArrayList<>();
            int order = 1;
            list.add(new Navigation("全站", "0", 1, true, true, order++));
            list.add(new Navigation("电影", "season$2", 1, true, true, order++));
            list.add(new Navigation("电视剧", "season$5", 1, true, true, order++));
            list.add(new Navigation("综艺", "season$7", 1, true, true, order++));
            list.add(new Navigation("纪录片", "season$3", 1, true, true, order++));
            list.add(new Navigation("国产动画", "season$4", 1, true, true, order++));
            list.add(new Navigation("番剧", "season$1", 1, true, true, order++));
            list.add(new Navigation("历史记录", "history$0", 1, true, true, order++));
            list.add(new Navigation("热门", "pop$1", 1, true, true, order++));
            list.add(new Navigation("科技", "188", 1, true, true, order++));
            list.add(new Navigation("知识", "36", 1, true, true, order++));
            list.add(new Navigation("动画", "1", 1, true, true, order++));
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
            list.add(new Navigation("国创相关", "168", 1, true, true, order++));
            list.add(new Navigation("原创", "origin$0", 1, true, true, order++));
            list.add(new Navigation("新人", "rookie$0", 1, true, true, order));

            list.add(new Navigation("数码", "95", 2, true, true, 1, 10));
            list.add(new Navigation("软件应用", "230", 2, true, true, 2, 10));
            list.add(new Navigation("计算机技术", "231", 2, true, true, 3, 10));
            list.add(new Navigation("极客DIY", "233", 2, true, true, 4, 10));

            list.add(new Navigation("科学科普", "201", 2, true, true, 1, 11));
            list.add(new Navigation("社科·法律·心理", "124", 2, true, true, 2, 11));
            list.add(new Navigation("人文历史", "228", 2, true, true, 3, 11));
            list.add(new Navigation("财经商业", "207", 2, true, true, 4, 11));
            list.add(new Navigation("校园学习", "208", 2, true, true, 5, 11));
            list.add(new Navigation("职业职场", "209", 2, true, true, 6, 11));
            list.add(new Navigation("设计·创意", "229", 2, true, true, 7, 11));
            navigationRepository.saveAll(list);
        }
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
