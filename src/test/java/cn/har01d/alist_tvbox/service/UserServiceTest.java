package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.auth.TokenService;
import cn.har01d.alist_tvbox.auth.UserToken;
import cn.har01d.alist_tvbox.domain.Role;
import cn.har01d.alist_tvbox.dto.UserDto;
import cn.har01d.alist_tvbox.dto.SessionDto;
import cn.har01d.alist_tvbox.entity.Session;
import cn.har01d.alist_tvbox.entity.SessionRepository;
import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.entity.UserRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TokenService tokenService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private UserService userService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        userService = new UserService(userRepository, sessionRepository, passwordEncoder, tokenService,
            new cn.har01d.alist_tvbox.service.backup.RestoreState("/data/does-not-exist-database-json.zip"),
            jdbcTemplate);
    }

    @Test
    void deleteShouldRemoveUsernameFromCache() {
        UserDto dto = new UserDto();
        dto.setUsername("alice");
        dto.setPassword("secret");

        when(userRepository.findByUsername("alice")).thenReturn(null);
        when(passwordEncoder.encode("secret")).thenReturn("encoded");
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        userService.create(dto);
        assertTrue(userService.isUsernameExist("alice"));

        when(userRepository.findAll()).thenReturn(List.of());
        userService.delete(2);

        assertFalse(userService.isUsernameExist("alice"));
    }

    @Test
    void updateAccountShouldRejectWrongOldPassword() {
        User user = new User();
        user.setId(1);
        user.setUsername("admin");
        user.setPassword("encoded-old");

        UserDto dto = new UserDto();
        dto.setUsername("admin");
        dto.setOldPassword("wrong-old");
        dto.setPassword("new-pass");

        when(userRepository.findByUsername("admin")).thenReturn(user);
        when(passwordEncoder.matches("wrong-old", "encoded-old")).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "token"));

        assertThrows(BadRequestException.class, () -> userService.updateAccount(dto, null, null));
        SecurityContextHolder.clearContext();
    }

    @Test
    void updateAccountShouldUpdateUsernameWithoutOldPasswordWhenPasswordIsBlank() {
        User user = new User();
        user.setId(1);
        user.setRole(Role.ADMIN);
        user.setUsername("admin");
        user.setPassword("encoded-old");

        UserDto dto = new UserDto();
        dto.setUsername("new-admin");
        dto.setPassword("");

        when(userRepository.findByUsername("admin")).thenReturn(user);
        when(userRepository.findByUsername("new-admin")).thenReturn(null);
        when(userRepository.save(user)).thenReturn(user);
        when(tokenService.encodeToken(1, "new-admin", "ADMIN", null, null)).thenReturn("token");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "token"));

        UserToken token = userService.updateAccount(dto, null, null);

        assertEquals("new-admin", user.getUsername());
        assertEquals("encoded-old", user.getPassword());
        assertEquals("token", token.getToken());
        verify(passwordEncoder, never()).matches(any(), any());
        verify(passwordEncoder, never()).encode(any());
        SecurityContextHolder.clearContext();
    }

    @Test
    void updateAccountShouldUpdatePasswordWhenOldPasswordMatches() {
        User user = new User();
        user.setId(1);
        user.setRole(Role.ADMIN);
        user.setUsername("admin");
        user.setPassword("encoded-old");

        UserDto dto = new UserDto();
        dto.setUsername("admin");
        dto.setOldPassword("old-pass");
        dto.setPassword("new-pass");

        when(userRepository.findByUsername("admin")).thenReturn(user);
        when(passwordEncoder.matches("old-pass", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("new-pass")).thenReturn("encoded-new");
        when(userRepository.save(user)).thenReturn(user);
        when(tokenService.encodeToken(1, "admin", "ADMIN", null, null)).thenReturn("token");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "token"));

        UserToken token = userService.updateAccount(dto, null, null);

        assertEquals("encoded-new", user.getPassword());
        assertEquals("token", token.getToken());
        SecurityContextHolder.clearContext();
    }

    @Test
    void ensureAdminOccupiesIdOneShouldMoveAdminWhenIdOneEmpty() {
        User admin = new User();
        admin.setId(5);
        admin.setUsername("admin");
        admin.setRole(Role.ADMIN);

        when(userRepository.findById(1)).thenReturn(Optional.empty());
        when(userRepository.findFirstByRoleOrderByIdAsc(Role.ADMIN)).thenReturn(Optional.of(admin));

        userService.ensureAdminOccupiesIdOne();

        verify(jdbcTemplate).update(eq("update x_user set id = 1 where id = ?"), eq(5));
    }

    @Test
    void ensureAdminOccupiesIdOneShouldNoopWhenIdOneOccupied() {
        User admin = new User();
        admin.setId(1);
        admin.setRole(Role.ADMIN);
        when(userRepository.findById(1)).thenReturn(Optional.of(admin));

        userService.ensureAdminOccupiesIdOne();

        verify(userRepository, never()).findFirstByRoleOrderByIdAsc(any());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void ensureAdminOccupiesIdOneShouldNoopWhenNoAdminExists() {
        when(userRepository.findById(1)).thenReturn(Optional.empty());
        when(userRepository.findFirstByRoleOrderByIdAsc(Role.ADMIN)).thenReturn(Optional.empty());

        userService.ensureAdminOccupiesIdOne();

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void listSessionsMarksCurrentAndParsesDevice() {
        Session a = new Session();
        a.setId(10);
        a.setToken("token-a");
        a.setUsername("admin");
        a.setRole("ADMIN");
        a.setLoginIp("1.2.3.4");
        a.setUserAgent("Mozilla/5.0 (Windows NT 10.0) Chrome/120.0 Safari/537.36");
        Session b = new Session();
        b.setId(11);
        b.setToken("token-b");
        b.setUsername("admin");
        b.setRole("ADMIN");
        b.setLoginIp("9.9.9.9");
        b.setUserAgent(null);

        when(sessionRepository.findAllByUsername("admin")).thenReturn(List.of(a, b));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "token-b"));

        var sessions = userService.listSessions("token-b");

        assertEquals(2, sessions.size());
        SessionDto current = sessions.stream().filter(SessionDto::isCurrent).findFirst().orElseThrow();
        assertEquals(11, current.getId());
        assertEquals("1.2.3.4", sessions.get(0).getLoginIp());
        assertEquals("Chrome 120", sessions.get(0).getBrowser());
        assertEquals("Windows", sessions.get(0).getOs());
        assertEquals("未知", sessions.get(1).getBrowser());
        SecurityContextHolder.clearContext();
    }

    @Test
    void revokeSessionRejectsOtherUsersSession() {
        Session s = new Session();
        s.setId(20);
        s.setUsername("alice");
        when(sessionRepository.findById(20)).thenReturn(Optional.of(s));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "token"));

        assertThrows(UserUnauthorizedException.class, () -> userService.revokeSession(20));
        verify(sessionRepository, never()).delete(any(Session.class));
        SecurityContextHolder.clearContext();
    }

    @Test
    void revokeSessionDeletesOwnedSession() {
        Session s = new Session();
        s.setId(21);
        s.setUsername("admin");
        when(sessionRepository.findById(21)).thenReturn(Optional.of(s));

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", "token"));
        userService.revokeSession(21);

        verify(sessionRepository).delete(s);
        SecurityContextHolder.clearContext();
    }
}
