package com.lcl.galaxy.lcl.galaxy.druid;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@MapperScan("com.lcl.galaxy.lcl.galaxy.druid.dao")
public class LclGalaxyDruidApplication {

    public static void main(String[] args) {
        SpringApplication.run(LclGalaxyDruidApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate(){
        return new RestTemplate();
    }

}
