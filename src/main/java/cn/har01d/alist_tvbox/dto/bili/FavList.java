package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class FavList {
    private int count;
    private List<FavFolder> list = new ArrayList<>();
}
