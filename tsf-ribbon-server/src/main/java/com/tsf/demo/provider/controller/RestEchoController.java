package com.tsf.demo.provider.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * 纯 REST 回显接口（无 POJO 序列化）：请求体原样接收为 String，响应体大小可调。
 * 用于"服务端 KeepAliveTimeout=60s 关闭空闲连接 + 客户端复用"专项验证：
 * 报文大小直接影响响应传输耗时（进而影响客户端 KeepAliveCache 计时起点与
 * 服务端空闲关闭时刻之间的窗口宽度），故 respSize/请求大小均为可配参数。
 */
@RestController
public class RestEchoController {

    private static final Logger log = LoggerFactory.getLogger(RestEchoController.class);

    /** 固定服务端时间戳（与真实毫秒时间戳等长，保证报文可复现） */
    public static final long FIXED_SERVER_TIME = 1700000000000L;

    /** 默认模拟业务睡眠时长（毫秒） */
    private static final long DEFAULT_SLEEP_MS = 10L;

    /** 睡眠参数上限（毫秒），防止误传超大值压住容器线程 */
    private static final long MAX_SLEEP_MS = 600000L;

    /** 响应体大小上限（4MB，防止误传超大值） */
    private static final int MAX_RESP_SIZE = 4 * 1024 * 1024;

    /**
     * REST 回显端点：请求体任意字符串（客户端以 JSON 形式发送可调大小的填充串），
     * 响应为固定格式的 JSON 文本（Content-Length 精确已知，非 chunked），
     * 头部内嵌 serverPort/serverTime/reqBytes 便于客户端观察命中实例与报文大小。
     * sleepMs 模拟业务耗时；respSize 指定响应字节数（0 = 与请求体等长）。
     */
    @PostMapping(value = "/api/rest")
    public ResponseEntity<String> rest(@RequestBody String body,
                                       @RequestParam(value = "sleepMs", required = false) String sleepMs,
                                       @RequestParam(value = "respSize", required = false, defaultValue = "0") int respSize,
                                       HttpServletRequest req) throws InterruptedException {
        long sleep = parseSleep(sleepMs);
        // 模拟业务处理耗时（可通过 URL 参数 sleepMs 配置，默认 10ms）
        Thread.sleep(sleep);
        int port = req.getLocalPort();
        log.info("[rest] port={} 收到请求: remote={}, reqBytes={}, sleepMs={}, respSize={}, thread={}",
                port, req.getRemoteAddr(), body.length(), sleep, respSize, Thread.currentThread().getName());

        String head = "{\"serverPort\":" + port + ",\"serverTime\":" + FIXED_SERVER_TIME
                + ",\"reqBytes\":" + body.length() + ",\"pad\":\"";
        int target = respSize <= 0 ? body.length() : Math.min(respSize, MAX_RESP_SIZE);
        StringBuilder sb = new StringBuilder(Math.max(target, head.length() + 2));
        sb.append(head);
        int pad = Math.max(0, target - head.length() - 2);
        for (int i = 0; i < pad; i++) {
            sb.append('x');
        }
        sb.append("\"}");
        log.info("[rest] port={} 返回响应: respBytes={}", port, sb.length());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(sb.toString());
    }

    @GetMapping("/")
    public String home(HttpServletRequest req) {
        return "tsf-ribbon-server port=" + req.getLocalPort() + " (/api/rest)";
    }

    /** 解析 sleepMs 参数: 空/非法/超范围回退默认值 */
    private static long parseSleep(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_SLEEP_MS;
        }
        try {
            long v = Long.parseLong(raw.trim());
            return (v < 0 || v > MAX_SLEEP_MS) ? DEFAULT_SLEEP_MS : v;
        } catch (NumberFormatException e) {
            return DEFAULT_SLEEP_MS;
        }
    }
}
