package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliList {
    private List<BiliBiliInfo> archives = new ArrayList<>();
    private Page page;

    @Data
    public static class Page {
        private int num;
        private int size;
        private int count;
    }
}
