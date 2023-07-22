package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class FavItems {
    private boolean has_more;
    private List<FavItem> medias = new ArrayList<>();
}
