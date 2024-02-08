package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.List;

@Data
public class TmdbCredits {
    private List<IdName> cast;
    private List<IdName> crew;
}
