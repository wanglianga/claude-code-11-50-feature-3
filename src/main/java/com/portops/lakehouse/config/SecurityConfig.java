package com.portops.lakehouse.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 本地开发用账号（README 中列为测试账号，生产环境应替换为外部身份源）：
 * ingest/ingest123 接入系统；dispatcher/disp123 调度；settlement/settle123 结算对账；admin/admin123 全部角色。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService users(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("ingest").password(encoder.encode("ingest123")).roles("INGEST").build(),
                User.withUsername("dispatcher").password(encoder.encode("disp123")).roles("DISPATCH").build(),
                User.withUsername("settlement").password(encoder.encode("settle123")).roles("SETTLEMENT").build(),
                User.withUsername("admin").password(encoder.encode("admin123"))
                        .roles("INGEST", "DISPATCH", "SETTLEMENT", "ADMIN").build());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // 事件接入与提交生成：接入系统账号
                        .requestMatchers(HttpMethod.POST, "/api/ingest/**").hasAnyRole("INGEST", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/commits/generate", "/api/commits/generate-all")
                        .hasAnyRole("INGEST", "ADMIN")
                        // 冲突裁定：调度
                        .requestMatchers(HttpMethod.POST, "/api/commits/conflicts/**").hasAnyRole("DISPATCH", "ADMIN")
                        // 调度视图：调度
                        .requestMatchers("/api/dispatch/**").hasAnyRole("DISPATCH", "ADMIN")
                        // 滞留分析（管理层/调度）：研究、区间版本、估计与报告
                        .requestMatchers("/api/dwell/**").hasAnyRole("DISPATCH", "ADMIN")
                        // 清单冻结、修订裁定、对账、结算：结算账号
                        .requestMatchers(HttpMethod.POST, "/api/manifests/**", "/api/revisions/**", "/api/recon/**")
                        .hasAnyRole("SETTLEMENT", "ADMIN")
                        .requestMatchers("/api/settlement/**").hasAnyRole("SETTLEMENT", "ADMIN")
                        // 开发工具（重置）：仅 admin
                        .requestMatchers("/api/dev/**").hasRole("ADMIN")
                        .anyRequest().authenticated());
        return http.build();
    }
}
