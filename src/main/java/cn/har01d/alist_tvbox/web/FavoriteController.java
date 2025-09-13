package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.service.FavoriteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {
    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @RequestMapping("/{id}/rate")
    public void rate(@PathVariable Integer id, Integer rating) {
        favoriteService.rate(id, rating);
    }

    @GetMapping("/{id}/rate")
    public Integer getRating(@PathVariable Integer id) {
        return favoriteService.getRating(id);
    }
}
