package org.example.takeout;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan(value = "org.example.takeout", annotationClass = Mapper.class)
public class TokeoutApplication {

    public static void main(String[] args) {
        SpringApplication.run(TokeoutApplication.class, args);
    }

}
