package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AliBatchRequest {
    private List<AliRequest> requests = new ArrayList<>();
    private String resource = "file";
}
