package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliSeasonInfoList {
    private List<BiliBiliSeasonInfo> list = new ArrayList<>();
    private int total;
}
