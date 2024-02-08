package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.List;

@Data
public class TmdbList {
    private List<TmdbDto> results;
}
