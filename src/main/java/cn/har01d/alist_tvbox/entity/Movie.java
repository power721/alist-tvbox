package cn.har01d.alist_tvbox.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
public class Movie {
    @Id
    private Integer id;
    private String name;
    private String genre;
    private String description;
    private String language;
    private String country;
    private String directors;
    private String editors;
    private String actors;
    private String cover;
    private String dbScore;
    @Column(name = "`year`")
    private Integer year;
}
