package cn.har01d.alist_tvbox.model;

import lombok.Data;

import java.time.Instant;

@Data
public class AliToken {
    private String key;
    private String value;
    private int accountId;
    private Instant modified;
}
