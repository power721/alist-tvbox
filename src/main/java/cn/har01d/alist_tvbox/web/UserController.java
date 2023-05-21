package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.service.UserService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/update")
    public void update(@RequestBody User user) {
        userService.update(user);
    }
}
