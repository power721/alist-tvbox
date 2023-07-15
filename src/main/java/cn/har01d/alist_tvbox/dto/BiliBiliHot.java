package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliHot {
    private List<BiliBiliInfo> list = new ArrayList<>();
}
