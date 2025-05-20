package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliCollection {
    private int count;
    private List<BiliBiliCollectionItem> list = new ArrayList<>();
}
