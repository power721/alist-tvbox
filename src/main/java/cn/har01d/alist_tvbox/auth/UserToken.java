package cn.har01d.alist_tvbox.auth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserToken {
    private int userId;
    private String username;
    private Collection<? extends GrantedAuthority> authorities;
    private String token;
}
