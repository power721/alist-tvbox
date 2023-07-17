package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BiliBiliChannel {
    private List<BiliBiliChannelItem> list = new ArrayList<>();
    private boolean has_more;
    private String offset;
}
