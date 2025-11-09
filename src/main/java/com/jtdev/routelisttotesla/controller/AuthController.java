package com.jtdev.routelisttotesla.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    @GetMapping("/login")
    public String login(@RequestParam(value = "error", required = false) String error,
                       @RequestParam(value = "logout", required = false) String logout,
                       Model model) {
        if (error != null) {
            model.addAttribute("error", "Access denied. You are not authorized to use this application.");
        }
        if (logout != null) {
            model.addAttribute("message", "You have been logged out successfully");
        }
        return "login";
    }

    @GetMapping("/")
    public String home(@AuthenticationPrincipal OidcUser principal, Model model) {
        if (principal != null) {
            model.addAttribute("username", principal.getEmail());
            model.addAttribute("name", principal.getFullName());
        }
        return "index";
    }
}
