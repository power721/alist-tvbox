package cn.har01d.alist_tvbox.config;

import cn.har01d.alist_tvbox.entity.UserRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class MyUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    public MyUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        cn.har01d.alist_tvbox.entity.User u = userRepository.findByUsername(username);
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (u == null) {
            return new User(username, UUID.randomUUID().toString(), authorities);
        }
        authorities.add(new SimpleGrantedAuthority("ADMIN"));
        return new User(String.valueOf(u.getId()), u.getPassword(), authorities);
    }
}
