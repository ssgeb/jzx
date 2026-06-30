package com.ruanzhu.doorhandlecatch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Web配置类，解决HTTP请求和响应中的中文乱码问题。
 * CORS统一由SecurityConfig配置，避免多套规则相互覆盖。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    
    /**
     * 配置字符编码过滤器
     */
    @Bean
    public Filter characterEncodingFilter() {
        CharacterEncodingFilter filter = new CharacterEncodingFilter();
        filter.setEncoding("UTF-8");
        filter.setForceEncoding(true);
        return filter;
    }

    /**
     * 配置消息转换器使用UTF-8编码
     */
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(new StringHttpMessageConverter(StandardCharsets.UTF_8));
    }
}
