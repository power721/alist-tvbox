package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
@TableGenerator(name = "tableGenerator", table = "id_generator", pkColumnName = "entity_name", valueColumnName = "next_id", allocationSize = 1)
public class Tmdb {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "tableGenerator")
    private Integer id;
    private Integer tmdbId;
    private String name;
    private String type = "movie";
    private String genre;
    private String description;
    private String language;
    private String country;
    private String directors;
    private String editors;
    private String actors;
    private String cover;
    private String score;
    @Column(name = "`year`")
    private Integer year;
}
