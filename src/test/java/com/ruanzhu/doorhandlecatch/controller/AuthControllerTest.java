package com.ruanzhu.doorhandlecatch.controller;

import com.ruanzhu.doorhandlecatch.dto.LoginRequest;
import com.ruanzhu.doorhandlecatch.dto.LoginResponse;
import com.ruanzhu.doorhandlecatch.security.LoginRateLimiter;
import com.ruanzhu.doorhandlecatch.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    @Test
    void loginSetsHttpOnlyJwtCookieForAuthenticatedFileRequests() {
        AuthService authService = mock(AuthService.class);
        LoginRateLimiter loginRateLimiter = mock(LoginRateLimiter.class);
        AuthController controller = new AuthController(authService, loginRateLimiter);
        ReflectionTestUtils.setField(controller, "jwtExpirationSeconds", 86400L);
        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("secret");
        LoginResponse loginResponse = new LoginResponse();
        loginResponse.setToken("jwt-token");
        loginResponse.setUsername("alice");
        when(loginRateLimiter.isAllowed("127.0.0.1")).thenReturn(true);
        when(authService.login(request)).thenReturn(loginResponse);

        ResponseEntity<?> response = controller.login(request, new MockHttpServletRequest(), new MockHttpServletResponse());

        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("DOOR_HANDLE_TOKEN=jwt-token");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Lax");
    }
}
