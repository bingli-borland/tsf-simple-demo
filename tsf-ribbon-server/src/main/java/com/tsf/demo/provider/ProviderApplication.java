package com.tsf.demo.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.stereotype.Component;
import org.springframework.tsf.annotation.EnableTsf;

/**
 * TSF 负载均衡服务端: /api/object 接收 Jackson POJO 报文并回显，多实例部署（不同端口）。
 * 支持 WAR 部署到外部 Tomcat/BES（SpringBootServletInitializer），也可 java -jar 独立运行。
 */
@SpringBootApplication
@EnableTsf
public class ProviderApplication extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(ProviderApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(ProviderApplication.class, args);
    }

    /**
     * 把嵌入式 Tomcat 连接器 keepAliveTimeout 设置为 ribbon-server.keep-alive-timeout-ms（毫秒）:
     * <=0 时跳过（不干预，使用引擎默认值 —— Boot 2.7 未设置 keepAliveTimeout 时回退
     * connectionTimeout=20s），>0 时设置到连接器。服务端主动关闭空闲 keep-alive 连接，
     * 配合客户端 JDK HttpURLConnection 连接缓存（约 5s）复现 "Unexpected end of file from server"。
     * BES 形态下此 Bean 不生效（BES 引擎走 server.bes.* 属性 / server.config），互不干扰。
     */
    @Component
    public static class KeepAliveTimeoutCustomizer
            implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

        @Value("${ribbon-server.keep-alive-timeout-ms:0}")
        private long keepAliveTimeoutMs;

        @Override
        public void customize(TomcatServletWebServerFactory factory) {
            if (keepAliveTimeoutMs <= 0) {
                return; // 0/负数 = 不干预，使用引擎默认
            }
            factory.addConnectorCustomizers(connector ->
                    connector.setProperty("keepAliveTimeout", String.valueOf(keepAliveTimeoutMs)));
        }
    }
}
