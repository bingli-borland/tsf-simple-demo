package com.tsf.demo.provider;

import com.tencent.tsf.serviceregistry.TsfRegistrationCustomizer;
import com.tencent.tsf.serviceregistry.TsfServletRegistrationCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.tsf.annotation.EnableTsf;

import javax.servlet.ServletContext;

@SpringBootApplication
@EnableFeignClients // 使用Feign微服务调用时请启用
@EnableTsf
public class ProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProviderApplication.class, args);
    }

    @Bean
    public TsfRegistrationCustomizer testTsfRegistrationCustomizer() {
        return new TestTsfRegistrationCustomizer();
    }
}