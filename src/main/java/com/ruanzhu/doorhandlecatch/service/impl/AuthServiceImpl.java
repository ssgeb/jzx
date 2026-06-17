package com.ruanzhu.doorhandlecatch.service.impl;

import com.ruanzhu.doorhandlecatch.common.BusinessException;
import com.ruanzhu.doorhandlecatch.dto.LoginRequest;
import com.ruanzhu.doorhandlecatch.dto.LoginResponse;
import com.ruanzhu.doorhandlecatch.entity.User;
import com.ruanzhu.doorhandlecatch.mapper.UserMapper;
import com.ruanzhu.doorhandlecatch.service.AuthService;
import com.ruanzhu.doorhandlecatch.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;

    @Override
    public LoginResponse login(LoginRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );

            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            String token = jwtUtil.generateToken(userDetails.getUsername());
            
            // 获取用户详细信息
            User user = userMapper.findByUsername(userDetails.getUsername());

            LoginResponse response = new LoginResponse();
            response.setToken(token);
            response.setUsername(user.getUsername());
            response.setEmail(user.getEmail());
            response.setPhone(user.getPhone());
            
            return response;
        } catch (BadCredentialsException e) {
            throw new BusinessException("用户名或密码错误");
        } catch (Exception e) {
            log.error("登录异常", e);
            throw new BusinessException("系统异常，请稍后再试");
        }
    }
} 