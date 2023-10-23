package cn.har01d.alist_tvbox.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class CheckinLog {
    private LocalDate date;
    private String name;
    private String status;
}
