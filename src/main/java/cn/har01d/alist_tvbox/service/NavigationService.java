package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.NavigationDto;
import cn.har01d.alist_tvbox.entity.Navigation;
import cn.har01d.alist_tvbox.entity.NavigationRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
                    item.setOrder(null);
                    parent.getChildren().add(item);
                } else {
                    result.add(item);
                }
            }
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
                if (item.getType() != 2) {
                    item.setOrder(updated.getOrder());
                }
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
