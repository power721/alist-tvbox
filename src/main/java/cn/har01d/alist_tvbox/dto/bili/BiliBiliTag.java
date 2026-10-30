package cn.har01d.alist_tvbox.dto.bili;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BiliBiliTag {
    private String tag_name;
}
