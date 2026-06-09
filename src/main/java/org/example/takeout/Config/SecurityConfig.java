package org.example.takeout.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF（RESTful API通常不需要）
            .csrf(AbstractHttpConfigurer::disable)
            // 禁用 HTTP Basic 认证
            .httpBasic(AbstractHttpConfigurer::disable)
            // 禁用表单登录
            .formLogin(AbstractHttpConfigurer::disable)
            // 禁用注销功能
            .logout(AbstractHttpConfigurer::disable)
            // 禁用会话固定保护
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            // 配置请求授权规则
            .authorizeHttpRequests(auth -> auth
                // 放行所有请求（开发阶段）
                .anyRequest().permitAll()
            );
        return http.build();
    }
}