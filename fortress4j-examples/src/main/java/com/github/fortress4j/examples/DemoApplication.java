package com.github.fortress4j.examples;


import com.github.fortress4j.spring.RateLimit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;


@SpringBootApplication
public class DemoApplication {
    static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @RestController
    static class DemoController{

        @RateLimit ("login")
        @PostMapping("/login")
        String login(){
            return "ok";
        }

        @RateLimit(value = "api", key="header('X-Api-Key')")
        @GetMapping("/api/hello")
        String hello(){
            return "hello";
        }

        @GetMapping("/open")
        String open(){
            return "open";
        }

    }
}
