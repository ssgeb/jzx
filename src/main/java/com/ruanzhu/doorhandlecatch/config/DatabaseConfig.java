package com.ruanzhu.doorhandlecatch.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.annotation.PostConstruct;

/**
 * 数据库配置类，用于解决中文乱码问题
 */
@Configuration
public class DatabaseConfig {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 应用启动时设置数据库连接的字符集
     */
    @PostConstruct
    public void setDatabaseEncoding() {
        jdbcTemplate.execute("SET NAMES utf8");
        jdbcTemplate.execute("SET CHARACTER SET utf8");
        jdbcTemplate.execute("SET character_set_connection=utf8");
        jdbcTemplate.execute("SET character_set_client=utf8");
        jdbcTemplate.execute("SET character_set_results=utf8");
    }
} 