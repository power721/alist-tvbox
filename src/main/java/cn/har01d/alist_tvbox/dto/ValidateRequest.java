package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ValidateRequest {
    private final String path;
    private List<ValidateRequest> children = new ArrayList<>();
}
