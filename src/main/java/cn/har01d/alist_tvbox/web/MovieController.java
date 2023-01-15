package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.MovieDto;
import cn.har01d.alist_tvbox.entity.Movie;
import cn.har01d.alist_tvbox.service.MovieService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/movies")
public class MovieController {
    private final MovieService movieService;

    public MovieController(MovieService movieService) {
        this.movieService = movieService;
    }

    @PostMapping
    public Movie create(@RequestBody MovieDto movie) {
        return movieService.create(movie);
    }

    @GetMapping("/get")
    public Movie read(Integer siteId, String path) {
        return movieService.read(siteId, path);
    }

    @GetMapping
    public Page<Movie> list(Pageable pageable) {
        return movieService.list(pageable);
    }

    @PostMapping("/{id}")
    public Movie update(@PathVariable Integer id, @RequestBody MovieDto dto) {
        return movieService.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) {
        movieService.delete(id);
    }
}
