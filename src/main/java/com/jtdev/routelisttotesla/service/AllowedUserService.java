package com.jtdev.routelisttotesla.service;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Getter
@Service
public class AllowedUserService {

    @Value("${app.auth.allowed-user}")
    private String allowedUsers;

    public boolean isUserAllowed(String email) {
        return allowedUsers.equalsIgnoreCase(email);
    }

}
