package com.tsf.demo.consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.tsf.annotation.EnableTsf;
import org.springframework.web.client.RestTemplate;

/**
 * TSF 负载均衡客户端（无 Servlet）：
 * 浏览器入口为 Spring MVC 的 ClientController（/、/RestCallServlet、/IdleReuseServlet、
 * /CacheStateServlet）。出站调用统一走 @LoadBalanced RestTemplate（JDK HttpURLConnection
 * + spring-retry），复现现场 "Unexpected end of file from server" 调用栈:
 * RetryLoadBalancerInterceptor → RetryTemplate → TsfBlockingLoadBalancerClient
 * → BlockingLoadBalancerClient → SimpleClientHttpResponse → HttpURLConnection。
 * 支持 WAR 部署到外部 Tomcat/BES，也可 java -jar 独立运行。
 */
@SpringBootApplication
@EnableTsf
public class ConsumerApplication extends SpringBootServletInitializer {

    /**
     * 复现现场调用栈的负载均衡 RestTemplate:
     * SimpleClientHttpRequestFactory = JDK HttpURLConnection（SimpleClientHttpResponse），
     * classpath 有 spring-retry 时 @LoadBalanced RestTemplate 自动挂 RetryLoadBalancerInterceptor。
     * 超时可经 ribbon-client.connect-timeout-ms / ribbon-client.read-timeout-ms 配置
     * （默认 3000/10000 与历史行为一致；慢业务场景 S4 须调大 read-timeout-ms）。
     */
    @LoadBalanced
    @Bean
    public RestTemplate restTemplate(
            @Value("${ribbon-client.connect-timeout-ms:3000}") int connectTimeout,
            @Value("${ribbon-client.read-timeout-ms:10000}") int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return new RestTemplate(factory);
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(ConsumerApplication.class);
    }

    public static void main(String[] args) {
        SpringApplication.run(ConsumerApplication.class, args);
    }
}
