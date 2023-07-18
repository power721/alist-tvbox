package cn.har01d.alist_tvbox.dto;

import cn.har01d.alist_tvbox.entity.Navigation;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class NavigationDto {
    private Integer id;
    private String name;
    private String value;
    private int type;
    private boolean show;
    private boolean reserved;
    private int order;
    private int parentId;
    private List<NavigationDto> children = new ArrayList<>();

    public NavigationDto() {
    }

    public NavigationDto(Navigation navigation) {
        setId(navigation.getId());
        setName(navigation.getName());
        setValue(navigation.getValue());
        setType(navigation.getType());
        setOrder(navigation.getOrder());
        setShow(navigation.isShow());
        setReserved(navigation.isReserved());
        setParentId(navigation.getParentId());
    }
}
