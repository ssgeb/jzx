package com.ruanzhu.doorhandlecatch.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

/**
 * 数据库初始化配置类
 * 提供手动初始化数据库的方法
 */
@Configuration
@Slf4j
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
            log.info("开始执行数据库初始化");
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
            populator.addScript(new ClassPathResource("db/init/schema-init.sql"));
            populator.execute(dataSource);
            log.info("数据库初始化完成");
        };
    }
}
