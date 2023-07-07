package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.IndexTemplateDto;
import cn.har01d.alist_tvbox.entity.IndexTemplate;
import cn.har01d.alist_tvbox.entity.IndexTemplateRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@Profile("xiaoya")
public class IndexTemplateService {
    private final IndexTemplateRepository indexTemplateRepository;

    public IndexTemplateService(IndexTemplateRepository indexTemplateRepository) {
        this.indexTemplateRepository = indexTemplateRepository;
    }

    public Page<IndexTemplate> list(Pageable pageable) {
        return indexTemplateRepository.findAll(pageable);
    }

    public IndexTemplate getById(Integer id) {
        return indexTemplateRepository.findById(id).orElseThrow(() -> new NotFoundException("索引模板不存在"));
    }

    public IndexTemplate create(IndexTemplateDto dto) {
        if (StringUtils.isBlank(dto.getName())) {
            throw new BadRequestException("名称不能为空");
        }
        if (StringUtils.isBlank(dto.getData())) {
            throw new BadRequestException("数据不能为空");
        }

        IndexTemplate template = new IndexTemplate();
        template.setSiteId(dto.getSiteId());
        template.setName(dto.getName());
        template.setData(dto.getData());
        template.setCreatedTime(Instant.now());
        return indexTemplateRepository.save(template);
    }

    public IndexTemplate update(Integer id, IndexTemplateDto dto) {
        if (StringUtils.isBlank(dto.getName())) {
            throw new BadRequestException("名称不能为空");
        }
        if (StringUtils.isBlank(dto.getData())) {
            throw new BadRequestException("数据不能为空");
        }

        IndexTemplate template = getById(id);
        template.setSiteId(dto.getSiteId());
        template.setName(dto.getName());
        template.setData(dto.getData());
        template.setCreatedTime(Instant.now());
        return indexTemplateRepository.save(template);
    }

    public void delete(Integer id) {
        indexTemplateRepository.deleteById(id);
    }
}