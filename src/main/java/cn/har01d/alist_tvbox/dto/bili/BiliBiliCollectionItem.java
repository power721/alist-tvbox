package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliCollectionItem {
    private String cover;
    private String title;
    private String intro;
    private long id;
    private long view_count;
    private int media_count;
}
