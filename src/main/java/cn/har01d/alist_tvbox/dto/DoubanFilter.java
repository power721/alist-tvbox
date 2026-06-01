package cn.har01d.alist_tvbox.dto;

import lombok.Data;

@Data
public class DoubanFilter {
    private String type;
    private String sort;
    private String genre;
    private String region;
    private String year;
    private String tvForm;
    private String varietyForm;
    private String platform;
    private String rank;

    public boolean hasTagFilters() {
        return isNotBlank(genre) || isNotBlank(region) || isNotBlank(year)
                || isNotBlank(tvForm) || isNotBlank(varietyForm) || isNotBlank(platform);
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isEmpty();
    }
}
