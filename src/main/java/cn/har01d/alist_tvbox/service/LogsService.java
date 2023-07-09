package cn.har01d.alist_tvbox.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@Profile("xiaoya")
public class LogsService {

    private String fixLine(String text) {
        return text
                .replace(" ERROR ", " <span class=\"error\">ERROR</span> ")
                .replace(" WARN ", " <span class=\"error\">WARN</span> ")
                .replace(" INFO ", " <span class=\"info\">INFO</span> ")
                .replace("\u001b[31m", "<span class=\"error\">")
                .replace("\u001b[36m", "<span class=\"success\">")
                .replace("\u001b[0m", " </span>");
    }

    public Page<String> getLogs(Pageable pageable) throws IOException {
        Path file = Paths.get("/opt/atv/log/app.log");
        List<String> lines = Files.readAllLines(file);
        int size = pageable.getPageSize();
        int start = (pageable.getPageNumber() - 1) * size;
        int end = start + size;
        if (end > lines.size()) {
            end = lines.size();
        }

        List<String> list = new ArrayList<>();
        if (start < end) {
            list = lines.subList(start, end).stream().map(this::fixLine).collect(Collectors.toList());
        }

        return new PageImpl<>(list, pageable, lines.size());
    }

}
