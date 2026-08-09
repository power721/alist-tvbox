package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@RequiredArgsConstructor
@Entity
public class PlaybackChangeSequence {
    @Id
    private Integer id;
    private long nextVal;
}
