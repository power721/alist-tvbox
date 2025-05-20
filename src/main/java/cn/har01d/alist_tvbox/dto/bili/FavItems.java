package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class FavItems {
    private boolean has_more;
    private Info info;
    private List<FavItem> medias = new ArrayList<>();

    @Data
    public static class Info {
        private String title;
        private String intro;
        private User upper;
    }

    @Data
    public static class User {
        private String name;
        private long mid;
    }
}
