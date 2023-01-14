package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.MovieDto;
import cn.har01d.alist_tvbox.entity.Movie;
import cn.har01d.alist_tvbox.entity.MovieRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.tvbox.MovieDetail;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MovieService {
    private final MovieRepository movieRepository;
    private final SiteService siteService;

    public MovieService(MovieRepository movieRepository, SiteService siteService) {
        this.movieRepository = movieRepository;
        this.siteService = siteService;
    }

    public Movie create(MovieDto dto) {
        if (StringUtils.isBlank(dto.getPath())) {
            throw new BadRequestException("路径不能为空");
        }
        Site site = siteService.getById(dto.getSiteId());
        Movie movie = movieRepository.findBySiteAndPath(site, dto.getPath()).orElse(new Movie());
        movie.setSite(site);
        movie.setPath(dto.getPath());
        movie.setName(dto.getName());
        movie.setCategory(dto.getCategory());
        movie.setActor(dto.getActor());
        movie.setDirector(dto.getDirector());
        movie.setLang(dto.getLang());
        movie.setArea(dto.getArea());
        movie.setYear(dto.getYear());
        movie.setCover(dto.getCover());
        movie.setContent(dto.getContent());
        return movieRepository.save(movie);
    }

    public Movie read(Integer siteId, String path) {
        return movieRepository.findBySiteIdAndPath(siteId, path).orElseThrow(() -> new NotFoundException("影视数据不存在"));
    }

    public Page<Movie> list(Pageable pageable) {
        return movieRepository.findAll(pageable);
    }

    public Movie update(Integer id, MovieDto dto) {
        if (StringUtils.isBlank(dto.getPath())) {
            throw new BadRequestException("路径不能为空");
        }
        Movie movie = movieRepository.findById(id).orElseThrow(() -> new NotFoundException("影视数据不存在"));
        movie.setName(dto.getName());
        movie.setCategory(dto.getCategory());
        movie.setActor(dto.getActor());
        movie.setDirector(dto.getDirector());
        movie.setLang(dto.getLang());
        movie.setArea(dto.getArea());
        movie.setYear(dto.getYear());
        movie.setCover(dto.getCover());
        movie.setContent(dto.getContent());
        return movieRepository.save(movie);
    }

    public void delete(Integer id) {
        movieRepository.deleteById(id);
    }

    public void readMetaData(MovieDetail movieDetail, String siteName, String path) {
        try {
            siteService.getByName(siteName)
                    .flatMap(site -> movieRepository.findBySiteAndPath(site, path))
                    .ifPresent(movie -> updateDetails(movieDetail, movie));
        } catch (Exception e) {
            log.warn("read meta data failed", e);
        }
    }

    private static void updateDetails(MovieDetail movieDetail, Movie movie) {
        if (StringUtils.isNotEmpty(movie.getName())) {
            movieDetail.setVod_name(movie.getName());
        }
        if (StringUtils.isNotEmpty(movie.getCover())) {
            movieDetail.setVod_pic(movie.getCover());
        }
        if (StringUtils.isNotEmpty(movie.getCategory())) {
            movieDetail.setType_name(movie.getCategory());
        }
        if (StringUtils.isNotEmpty(movie.getActor())) {
            movieDetail.setVod_actor(movie.getActor());
        }
        if (StringUtils.isNotEmpty(movie.getDirector())) {
            movieDetail.setVod_director(movie.getDirector());
        }
        if (StringUtils.isNotEmpty(movie.getLang())) {
            movieDetail.setVod_lang(movie.getLang());
        }
        if (StringUtils.isNotEmpty(movie.getArea())) {
            movieDetail.setVod_area(movie.getArea());
        }
        if (movie.getYear() != null) {
            movieDetail.setVod_year(String.valueOf(movie.getYear()));
        }
        if (StringUtils.isNotEmpty(movie.getContent())) {
            movieDetail.setVod_content(movie.getContent());
        }
    }
}
