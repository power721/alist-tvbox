package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.NavigationDto;
import cn.har01d.alist_tvbox.dto.NavigationList;
import cn.har01d.alist_tvbox.entity.Navigation;
import cn.har01d.alist_tvbox.service.NavigationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/nav")
public class NavigationController {
    private final NavigationService navigationService;

    public NavigationController(NavigationService navigationService) {
        this.navigationService = navigationService;
    }

    @GetMapping
    public List<NavigationDto> list() {
        return navigationService.list();
    }

    @PutMapping
    public void saveAll(@RequestBody NavigationList dto) {
        navigationService.saveAll(dto.getList());
    }

    @PostMapping
    public Navigation create(@RequestBody NavigationDto dto) {
        return navigationService.create(dto);
    }

    @PostMapping("/{id}")
    public Navigation update(@PathVariable Integer id, @RequestBody NavigationDto dto) {
        return navigationService.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) {
        navigationService.delete(id);
    }

}
