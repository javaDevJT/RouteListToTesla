package com.jtdev.routelisttotesla.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    @GetMapping("/login")
    public String login(@RequestParam(value = "error", required = false) String error,
                       @RequestParam(value = "logout", required = false) String logout,
                       Model model, HttpServletRequest request) {
        model.addAttribute("cspNonce", request.getAttribute("cspNonce"));
        if (error != null) {
            model.addAttribute("error", "TAP access was denied, expired, or could not be verified. Check your TAP access and sign in again.");
        }
        if (logout != null) {
            model.addAttribute("message", "You have been logged out successfully");
        }
        return "login";
    }

    @GetMapping("/")
    public String home(@AuthenticationPrincipal OAuth2User principal, Model model, HttpServletRequest request) {
        model.addAttribute("cspNonce", request.getAttribute("cspNonce"));
        if (principal != null) {
            model.addAttribute("username", principal.getAttribute("email"));
            model.addAttribute("name", principal.getAttribute("name"));
        }
        return "index";
    }
}
