package com.jtdev.routelisttotesla;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class RouteListToTeslaApplication {

    public static void main(String[] args) {
        SpringApplication.run(RouteListToTeslaApplication.class, args);
    }

}
