package com.example.FieldFinder.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WelcomeController {

    @GetMapping("/")
    public String homePage() {
        return ("Welcome to our page");
    }
}
