package com.ruanzhu.doorhandlecatch.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * 专门解决MybatisPlus自动配置中ddlApplicationRunner冲突问题的配置类
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MybatisPlusRunnerConfig {
    
    /**
     * 此方法将会使用空的配置覆盖MybatisPlus中的ddlApplicationRunner
     * 使用不同的Bean名称避免冲突
     */
    @Bean("mybatisPlusDdlRunner")
    @ConditionalOnMissingBean(name = "ddlApplicationRunner")
    public Object ddlApplicationRunnerReplacement() {
        // 返回一个对象而不是ApplicationRunner，避免类型检查问题
        return new Object();
    }
} 