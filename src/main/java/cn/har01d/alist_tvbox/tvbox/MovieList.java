package cn.har01d.alist_tvbox.tvbox;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MovieList {
    private List<MovieDetail> list = new ArrayList<>();

    public int getTotal() {
        return list.size();
    }
}
