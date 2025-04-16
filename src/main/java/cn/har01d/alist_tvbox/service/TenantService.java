package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Tenant;
import cn.har01d.alist_tvbox.entity.TenantRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TenantService {
    private final ThreadLocal<String> threadLocal = new ThreadLocal<>();
    private final TenantRepository tenantRepository;
    private List<Tenant> tenants;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    public List<Tenant> list() {
        tenants = tenantRepository.findAll();
        return tenants;
    }

    public Tenant create(Tenant tenant) {
        if (tenantRepository.existsByName(tenant.getName())) {
            throw new BadRequestException("名称已经存在");
        }
        fixRules(tenant);
        tenants = null;
        return tenantRepository.save(tenant);
    }

    public Tenant update(Integer id, Tenant tenant) {
        Tenant other = tenantRepository.findByName(tenant.getName()).orElse(null);
        if (other != null && !id.equals(other.getId())) {
            throw new BadRequestException("名称已经存在");
        }
        tenant.setId(id);
        fixRules(tenant);
        tenants = null;
        return tenantRepository.save(tenant);
    }

    public void delete(Integer id) {
        tenantRepository.deleteById(id);
        tenants = null;
    }

    private void fixRules(Tenant tenant) {
        if (StringUtils.isNotBlank(tenant.getInclude())) {
            tenant.setInclude(Arrays.stream(tenant.getInclude().split(",")).filter(StringUtils::isNotBlank).map(this::decode).collect(Collectors.joining(",")));
        }
        if (StringUtils.isNotBlank(tenant.getExclude())) {
            tenant.setExclude(Arrays.stream(tenant.getExclude().split(",")).filter(StringUtils::isNotBlank).map(this::decode).collect(Collectors.joining(",")));
        }
    }

    public boolean valid(String path) {
        Tenant tenant = getTenant();
        if (tenant == null) {
            return true;
        }
        if (exclude(tenant.getExclude(), path)) {
            return false;
        }
        return include(tenant.getInclude(), path);
    }

    private Tenant getTenant() {
        String name = threadLocal.get();
        if (name == null) {
            return null;
        }
        if (tenants == null) {
            list();
        }
        for (Tenant tenant : tenants) {
            if (tenant.getName().equals(name)) {
                return tenant;
            }
        }
        return null;
    }

    private boolean exclude(String exclude, String path) {
        if (StringUtils.isBlank(exclude)) {
            return false;
        }

        for (String rule : exclude.split(",")) {
            if (rule.startsWith("/")) {
                if (path.startsWith(rule)) {
                    return true;
                }
            } else {
                if (path.contains(rule)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean include(String include, String path) {
        if (StringUtils.isBlank(include)) {
            return true;
        }

        for (String rule : include.split(",")) {
            if (path.startsWith(rule) || rule.startsWith(path)) {
                return true;
            }
        }
        return false;
    }

    public void setTenant(String name) {
        threadLocal.set(name);
    }

    public void clear() {
        threadLocal.remove();
    }

    private String decode(String url) {
        return URLDecoder.decode(url, StandardCharsets.UTF_8);
    }
}
