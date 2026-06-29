package com.ruanzhu.doorhandlecatch.service;

import com.ruanzhu.doorhandlecatch.entity.User;
import com.ruanzhu.doorhandlecatch.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserDetailsServiceImplTest {

    @Test
    void storedAdminRoleMapsToExactlyRoleAdminCaseInsensitively() {
        UserMapper userMapper = mock(UserMapper.class);
        User user = userWithRole("alice", "secret", "aDmIn");
        when(userMapper.findByUsername("alice")).thenReturn(user);

        UserDetails details = new UserDetailsServiceImpl(userMapper).loadUserByUsername("alice");

        assertThat(authorities(details)).containsExactly("ROLE_ADMIN");
    }

    @Test
    void unknownRoleMapsToExactlyRoleOperator() {
        UserMapper userMapper = mock(UserMapper.class);
        User user = userWithRole("alice", "secret", "SUPERVISOR");
        when(userMapper.findByUsername("alice")).thenReturn(user);

        UserDetails details = new UserDetailsServiceImpl(userMapper).loadUserByUsername("alice");

        assertThat(authorities(details)).containsExactly("ROLE_OPERATOR");
    }

    @Test
    void blankRoleMapsToExactlyRoleOperatorEvenForAdminUsername() {
        UserMapper userMapper = mock(UserMapper.class);
        User user = new User();
        user.setUsername("admin");
        user.setPassword("secret");
        when(userMapper.findByUsername("admin")).thenReturn(user);

        UserDetails details = new UserDetailsServiceImpl(userMapper).loadUserByUsername("admin");

        assertThat(authorities(details)).containsExactly("ROLE_OPERATOR");
    }

    private User userWithRole(String username, String password, String role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        user.setRole(role);
        return user;
    }

    private java.util.List<String> authorities(UserDetails details) {
        return details.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }
}
