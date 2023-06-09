package cn.har01d.alist_tvbox.entity;


import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.OneToOne;

@Getter
@Setter
@ToString
@RequiredArgsConstructor
@Entity
public class Alias {
    @Id
    private String name;
    private String alias;
    @OneToOne
    private Movie movie;
}
