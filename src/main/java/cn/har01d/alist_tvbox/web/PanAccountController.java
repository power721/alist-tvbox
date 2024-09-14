package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.PanAccount;
import cn.har01d.alist_tvbox.entity.PikPakAccount;
import cn.har01d.alist_tvbox.service.PanAccountService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/pan/accounts")
public class PanAccountController {
    private final PanAccountService panAccountService;

    public PanAccountController(PanAccountService panAccountService) {
        this.panAccountService = panAccountService;
    }

    @GetMapping
    public List<PanAccount> list() {
        return panAccountService.list();
    }

    @PostMapping
    public PanAccount create(@RequestBody PanAccount account) {
        return panAccountService.create(account);
    }

    @PostMapping("/{id}")
    public PanAccount update(@PathVariable Integer id, @RequestBody PanAccount account) {
        return panAccountService.update(id, account);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) {
        panAccountService.delete(id);
    }
}
