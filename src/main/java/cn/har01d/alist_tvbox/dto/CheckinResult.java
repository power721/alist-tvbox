package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
public class CheckinResult {
    private String nickname;
    private int signInCount;
    private Instant checkinTime;
    private List<Map<String, Object>> signInLogs;
}
