package cn.har01d.alist_tvbox.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class TmdbDto {
    private Integer id;
    private String title;
    private String name;
    private String overview;
    private List<IdName> genres;
    @JsonProperty("spoken_languages")
    private List<IdName> language;
    @JsonProperty("production_countries")
    private List<IdName> country;
    private TmdbCredits credits;
    @JsonProperty("poster_path")
    private String cover;
    @JsonProperty("vote_average")
    private String score;
    @JsonProperty("release_date")
    private LocalDate date;
    @JsonProperty("first_air_date")
    private LocalDate firstDate;
}
