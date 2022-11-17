package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.model.FileNameInfo;

import java.text.Collator;
import java.util.Comparator;

public class FileNameComparator implements Comparator<FileNameInfo> {
    private final Comparator<Object> comparator = Collator.getInstance(java.util.Locale.CHINA);

    @Override
    public int compare(FileNameInfo o1, FileNameInfo o2) {
        if (o1.getPrefix().equals(o2.getPrefix())) {
            int result = Double.compare(o1.getNumber(), o2.getNumber());
            if (result != 0) {
                return result;
            }
        }
        return comparator.compare(o1.getName(), o2.getName());
    }
}
