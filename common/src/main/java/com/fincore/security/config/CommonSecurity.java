package com.fincore.security.config;

import com.fincore.security.filter.InternalApiKeyFilter;
import com.fincore.security.filter.JwtAuthenticationFilter;
import com.fincore.security.jwt.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** The security setup every FinCore service shares; a service only adds its own public paths. */
public final class CommonSecurity {

    private CommonSecurity() {}

    public static HttpSecurity statelessApi(
            HttpSecurity http,
            JwtService jwtService,
            String internalApiKey,
            Customizer<
                            AuthorizeHttpRequestsConfigurer<HttpSecurity>
                                    .AuthorizationManagerRequestMatcherRegistry>
                    publicPaths)
            throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a -> {
                            a.requestMatchers(
                                            "/actuator/health/**",
                                            "/actuator/info",
                                            "/actuator/prometheus")
                                    .permitAll();
                            a.requestMatchers(
                                            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                                    .permitAll();
                            publicPaths.customize(a);
                            a.requestMatchers("/internal/**").hasRole(InternalApiKeyFilter.ROLE);
                            a.anyRequest().authenticated();
                        })
                .exceptionHandling(
                        e ->
                                e.authenticationEntryPoint(
                                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(
                        new InternalApiKeyFilter(internalApiKey),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtService),
                        UsernamePasswordAuthenticationFilter.class);
    }
}
