package com.jtdev.routelisttotesla.config;

import com.jtdev.routelisttotesla.model.FleetApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FleetApiClientConfig {

    @Value("${teslemetry.oauth.token}")
    private String accessToken;

    @Bean
    public FleetApi fleetApi() {
        return FleetApi.newBuilder().accessToken(accessToken).baseUrl("https://api.teslemetry.com/api/1").logRequests(true).build();
    }
}
