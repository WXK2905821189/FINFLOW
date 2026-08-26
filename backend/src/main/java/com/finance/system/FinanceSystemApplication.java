package com.finance.system;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.finance.system.**.mapper")
public class FinanceSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinanceSystemApplication.class, args);
    }
}
