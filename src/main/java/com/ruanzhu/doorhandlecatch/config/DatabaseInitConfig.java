package com.ruanzhu.doorhandlecatch.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * 数据库初始化配置类
 * 提供手动初始化数据库的方法
 */
@Configuration
public class DatabaseInitConfig {

    @Autowired
    private DataSource dataSource;

    /**
     * 仅在首次运行时初始化数据库的方法
     * 使用profile="init"来控制是否执行
     */
    @Bean
    @Profile("init")
    public CommandLineRunner initDatabase() {
        return args -> {
            System.out.println("====== 执行数据库初始化 ======");
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("db/init/schema-init.sql"));
            populator.execute(dataSource);
            System.out.println("====== 数据库初始化完成 ======");
        };
    }
} 