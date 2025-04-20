package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.service.DriverAccountService;
import cn.har01d.alist_tvbox.service.QuarkUCTV;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/pan/accounts")
public class PanAccountController {
    private final DriverAccountService driverAccountService;

    public PanAccountController(DriverAccountService driverAccountService) {
        this.driverAccountService = driverAccountService;
    }

    @GetMapping
    public List<DriverAccount> list() {
        return driverAccountService.list();
    }

    @PostMapping
    public DriverAccount create(@RequestBody DriverAccount account) {
        return driverAccountService.create(account);
    }

    @PostMapping("/{id}")
    public DriverAccount update(@PathVariable Integer id, @RequestBody DriverAccount account) {
        return driverAccountService.update(id, account);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) {
        driverAccountService.delete(id);
    }

    @PostMapping("/-/qr")
    public QuarkUCTV.LoginResponse getQrCode(String type) {
        return driverAccountService.getQrCode(type);
    }

    @PostMapping("/-/token")
    public String getRefreshToken(String type, String queryToken) {
        return driverAccountService.getRefreshToken(type, queryToken);
    }
}
