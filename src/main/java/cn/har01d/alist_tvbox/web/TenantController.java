package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.Tenant;
import cn.har01d.alist_tvbox.service.TenantService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tenants")
public class TenantController {
    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @RequestMapping
    public List<Tenant> list() {
        return tenantService.list();
    }

    @PostMapping
    public Tenant create(@RequestBody Tenant tenant) {
        return tenantService.create(tenant);
    }

    @PostMapping("/{id}")
    public Tenant update(@PathVariable Integer id, @RequestBody Tenant tenant) {
        return tenantService.update(id, tenant);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) {
        tenantService.delete(id);
    }
}
