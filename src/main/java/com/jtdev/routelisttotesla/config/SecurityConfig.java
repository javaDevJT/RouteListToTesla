package com.jtdev.routelisttotesla.config;

import com.jtdev.routelisttotesla.service.AllowedUserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

import java.util.HashSet;
import java.util.Set;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AllowedUserService allowedUserService;

    public SecurityConfig(AllowedUserService allowedUserService) {
        this.allowedUserService = allowedUserService;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/login", "/css/**", "/js/**", "/images/**", "/error").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .failureHandler(new SimpleUrlAuthenticationFailureHandler("/login?error=true"))
                .userInfoEndpoint(userInfo -> userInfo
                    .oidcUserService(this.oidcUserService())
                )
            )
            .sessionManagement(session -> session
                .maximumSessions(1)
                .maxSessionsPreventsLogin(false)
                .and()
                .sessionFixation().changeSessionId()
                .sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.IF_REQUIRED)
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout=true")
                .permitAll()
            )
            .csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Bean
    public OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        final OidcUserService delegate = new OidcUserService();

        return (userRequest) -> {
            OidcUser oidcUser = delegate.loadUser(userRequest);
            String email = oidcUser.getEmail();
            
            if (!allowedUserService.isUserAllowed(email)) {
                throw new OAuth2AuthenticationException(
                    new OAuth2Error("access_denied", "User not authorized", null));
            }

            Set<GrantedAuthority> mappedAuthorities = new HashSet<>();
            mappedAuthorities.add(new SimpleGrantedAuthority("ROLE_USER"));

            return new DefaultOidcUser(mappedAuthorities, oidcUser.getIdToken(), oidcUser.getUserInfo());
        };
    }
}
