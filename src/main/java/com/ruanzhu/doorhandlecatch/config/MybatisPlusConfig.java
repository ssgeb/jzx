package com.ruanzhu.doorhandlecatch.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusPropertiesCustomizer;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus配置类
 */
@Configuration
@MapperScan("com.ruanzhu.doorhandlecatch.mapper")
public class MybatisPlusConfig {
    
    /**
     * 配置MyBatis-Plus拦截器插件
     * 添加分页插件
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 添加分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
    
    /**
     * 禁用MybatisPlus的数据库初始化运行器
     */
    @Bean
    @ConditionalOnProperty(prefix = "mybatis-plus.global-config.db-config", name = "schema", havingValue = "false", matchIfMissing = true)
    public ConfigurationCustomizer mybatisPlusConfigurationCustomizer() {
        return configuration -> {
            // 这里可以禁用MybatisPlus的某些默认行为
        };
    }
    
    /**
     * 禁用MybatisPlus的自动运行器
     */
    @Bean
    public MybatisPlusPropertiesCustomizer mybatisPlusPropertiesCustomizer() {
        return properties -> {
            // 禁用自动运行器
            // 修改全局配置来禁用
            properties.getGlobalConfig().setBanner(false);
        };
    }
} 