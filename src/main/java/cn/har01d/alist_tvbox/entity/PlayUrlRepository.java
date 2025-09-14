package cn.har01d.alist_tvbox.entity;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PlayUrlRepository extends JpaRepository<PlayUrl, Integer> {
    List<PlayUrl> findByTimeBeforeAndRatingIsNull(Instant time);

    PlayUrl findFirstBySiteAndPath(int site, String path, Sort sort);
}
