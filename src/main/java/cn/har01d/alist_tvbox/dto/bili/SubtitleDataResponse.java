package cn.har01d.alist_tvbox.dto.bili;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SubtitleDataResponse {
    private String lang;
    private String type;
    private String version;
    private String Stroke;
    private List<SubtitleData> body = new ArrayList<>();
}
