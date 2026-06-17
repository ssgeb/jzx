package com.ruanzhu.doorhandlecatch.dto;

import lombok.Data;

@Data
public class LoginResponse {
    private String token;
    private String username;
    private String email;
    private String phone;
    private String realName;
} 