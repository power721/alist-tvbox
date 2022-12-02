package cn.har01d.alist_tvbox.tvbox;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MovieList {
    private int page = 1;
    private int pagecount = 1;
    private int limit = 100;
    private int total;
    private List<MovieDetail> list = new ArrayList<>();
}
