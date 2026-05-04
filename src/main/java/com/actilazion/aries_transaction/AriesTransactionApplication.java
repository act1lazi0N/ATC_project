package com.actilazion.aries_transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class AriesTransactionApplication {
    public static void main(String[] args) {
        SpringApplication.run(AriesTransactionApplication.class, args);
    }

}
