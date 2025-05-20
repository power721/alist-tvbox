package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliSeasonInfoList2 {
    private int total;
    private List<BiliBiliSeasonInfo2> list = new ArrayList<>();
}
