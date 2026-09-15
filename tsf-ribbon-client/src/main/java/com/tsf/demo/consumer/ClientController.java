package com.tsf.demo.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 浏览器测试入口（Spring MVC）：
 * /                      欢迎页（实例列表 + 当前 KeepAliveCache 状态）
 * /RestCallServlet       通用复现流程: 预热 → 空闲 gapMs → 探测 → 恢复
 * /IdleReuseServlet      60s 空闲复用专项（核心）: 精确到毫秒的 gap 扫描/定点复测
 * /CacheStateServlet     打印 JDK KeepAliveCache 内部状态（nap/空闲时长/本地端口）
 *
 * 出站调用统一走 @LoadBalanced RestTemplate（SimpleClientHttpRequestFactory = JDK
 * HttpURLConnection + SimpleClientHttpResponse），调用栈与现场报错一致:
 * RetryLoadBalancerInterceptor → RetryTemplate → TsfBlockingLoadBalancerClient
 * → BlockingLoadBalancerClient → SimpleClientHttpResponse → HttpURLConnection。
 * 报文为纯 REST 字符串（大小可调），不做 POJO 序列化。
 */
@RestController
public class ClientController {

    private static final Logger log = LoggerFactory.getLogger(ClientController.class);

    /** 负载均衡服务名（与 spring.cloud.discovery.client.simple.instances 的 key 一致） */
    static final String SERVICE_ID = "tsf-ribbon-server";

    /** 默认模拟业务睡眠时长（毫秒） */
    private static final long DEFAULT_SLEEP_MS = 10L;

    /** 睡眠/等待参数上限（毫秒） */
    private static final long MAX_SLEEP_MS = 600000L;

    /** 默认请求报文大小（字节） */
    private static final int DEFAULT_SIZE = 1024;

    /** 报文大小上限（字节） */
    private static final int MAX_SIZE = 4 * 1024 * 1024;

    /**
     * 专项场景默认空闲等待: 60s - 10ms。
     * 客户端缓存 nap=60s（来自服务端 Keep-Alive: timeout=60 响应头），服务端空闲 60s 关闭，
     * 复用失败窗口在 gap≈60s 附近的毫秒级区间，报文越大窗口越宽。
     */
    private static final long DEFAULT_GAP_MS = 59990L;

    private static final Pattern PORT_PATTERN = Pattern.compile("\"serverPort\":(\\d+)");

    private final RestTemplate restTemplate;

    private final DiscoveryClient discoveryClient;

    public ClientController(RestTemplate restTemplate, DiscoveryClient discoveryClient) {
        this.restTemplate = restTemplate;
        this.discoveryClient = discoveryClient;
    }

    @GetMapping("/")
    public String home() {
        List<ServiceInstance> instances = discoveryClient.getInstances(SERVICE_ID);
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><title>tsf-ribbon-client</title>");
        html.append("<style>body{font-family:sans-serif;margin:24px;}"
                + "table{border-collapse:collapse;}th,td{border:1px solid #999;padding:4px 10px;text-align:left;}"
                + "th{background:#eee;}</style></head><body>");
        html.append("<h2>tsf-ribbon-client（RestTemplate 负载均衡 + 60s 空闲复用专项）</h2>");

        html.append("<h3>负载均衡服务端实例列表（").append(SERVICE_ID).append("）</h3><ol>");
        if (instances.isEmpty()) {
            html.append("<li style=\"color:red\">（空：请检查 spring.cloud.discovery.client.simple.instances 配置）</li>");
        }
        for (ServiceInstance instance : instances) {
            html.append("<li>").append(instance.getUri()).append("</li>");
        }
        html.append("</ol>");

        html.append("<h3>当前 JDK KeepAliveCache 状态（nap 应=服务端 Keep-Alive: timeout=60 头）</h3><pre>")
                .append(cacheStateText()).append("</pre>");

        html.append("<h3>测试入口</h3><table>");
        html.append("<tr><th>入口</th><th>说明</th><th>URL</th></tr>");
        html.append("<tr><td>IdleReuseServlet ★</td><td>60s 空闲复用专项: 每轮 预热→精确空闲 gap→探测→恢复，"
                + "记录是否复用缓存连接与服务端返回形态。参数: size(请求字节), respSize(响应字节,0=同请求), "
                + "gapMs(默认 59990), gapList(逗号分隔逐轮扫描), cycles(轮数), probeCount, jitterMs, serverSleepMs</td>"
                + "<td><a href=\"IdleReuseServlet\">IdleReuseServlet</a></td></tr>");
        html.append("<tr><td>RestCallServlet</td><td>通用复现流程: 预热所有实例 → 空闲 gapMs → 探测 → 恢复。"
                + "参数: size, respSize, gapMs(默认 59990), calls, serverSleepMs</td>"
                + "<td><a href=\"RestCallServlet\">RestCallServlet</a></td></tr>");
        html.append("<tr><td>CacheStateServlet</td><td>打印 KeepAliveCache 内部状态（nap/空闲时长/本地端口）</td>"
                + "<td><a href=\"CacheStateServlet\">CacheStateServlet</a></td></tr>");
        html.append("</table></body></html>");
        return html.toString();
    }

    @GetMapping("/CacheStateServlet")
    public String cacheState() {
        return page("KeepAliveCache 内部状态", "<pre>" + cacheStateText() + "</pre>");
    }

    /**
     * 60s 空闲复用专项（核心验证场景）。
     * 每轮流程: 预热(建连/复用并刷新缓存) → 精确空闲 gap → 探测 × probeCount → 恢复。
     * gap 与客户端缓存 nap(=60s) 同源对齐，扫描 [60-δ, 60) 窗口即可观察:
     *   - 复用服务端已关闭连接 → EOF/reset 报错（现场 "Unexpected end of file from server"）
     *   - 复用成功 → 说明 gap 落在服务端关闭之前
     * 每次调用前后抓取 KeepAliveCache 快照，结合本地端口判断"复用 vs 新建"。
     */
    @GetMapping("/IdleReuseServlet")
    public String idleReuse(@RequestParam(value = "size", required = false, defaultValue = "1024") int size,
                            @RequestParam(value = "respSize", required = false, defaultValue = "0") int respSize,
                            @RequestParam(value = "gapMs", required = false, defaultValue = "0") long gapMs,
                            @RequestParam(value = "gapList", required = false) String gapList,
                            @RequestParam(value = "cycles", required = false, defaultValue = "1") int cycles,
                            @RequestParam(value = "probeCount", required = false, defaultValue = "1") int probeCount,
                            @RequestParam(value = "jitterMs", required = false, defaultValue = "0") long jitterMs,
                            @RequestParam(value = "serverSleepMs", required = false, defaultValue = "10") long serverSleepMs)
            throws InterruptedException {
        int reqSize = clampSize(size);
        int respSizeClamped = Math.max(0, Math.min(respSize, MAX_SIZE));
        long srvSleep = clamp(serverSleepMs, 0, MAX_SLEEP_MS, DEFAULT_SLEEP_MS);
        int probes = (int) clamp(probeCount, 1, 20, 1);

        List<Long> gaps = parseGaps(gapList, gapMs <= 0 ? DEFAULT_GAP_MS : gapMs, cycles);
        StringBuilder body = new StringBuilder();
        body.append("<p>实例数=").append(discoveryClient.getInstances(SERVICE_ID).size())
                .append(", 请求报文=").append(reqSize).append("B, 响应报文=")
                .append(respSizeClamped == 0 ? "同请求" : respSizeClamped + "B")
                .append(", 探测次数/轮=").append(probes)
                .append(", jitter=").append(jitterMs).append("ms, serverSleepMs=").append(srvSleep)
                .append("<br>客户端缓存 nap 来自服务端 Keep-Alive: timeout=60 响应头（毕昇 1.8.0_452 实测），"
                        + "服务端 KeepAliveTimeout=60s；错误窗口在 gap≈60s 附近的毫秒级区间。</p>");

        for (int cycle = 1; cycle <= gaps.size(); cycle++) {
            long gap = gaps.get(cycle - 1);
            if (jitterMs > 0) {
                long j = (long) ((Math.random() * 2 - 1) * jitterMs);
                gap = clamp(gap + j, 0, MAX_SLEEP_MS, gap);
            }
            body.append(runIdleCycle(cycle, gap, reqSize, respSizeClamped, srvSleep, probes));
        }
        return page("IdleReuseServlet（60s 空闲复用专项）", body.toString());
    }

    /** 单轮: 预热 → 精确空闲 gap → 探测 ×N → 恢复，输出时间线与缓存快照 */
    private String runIdleCycle(int cycle, long gap, int reqSize, int respSize, long srvSleep, int probes)
            throws InterruptedException {
        String payload = buildPayload(reqSize);
        StringBuilder body = new StringBuilder();
        body.append("<hr><p><b>第 ").append(cycle).append(" 轮: gap=").append(gap).append("ms, 报文=")
                .append(reqSize).append("B</b></p>");

        body.append(callRest(payload, respSize, "预热", srvSleep));

        // 精确空闲: 以预热响应读完时刻为 T0（与 KeepAliveCache idleStartTime 同源），纳秒级对齐
        long t0 = System.nanoTime();
        body.append("<p>... 空闲 ").append(gap).append("ms（T0=")
                .append(ts(t0)).append(", 客户端缓存nap=60s, 服务端KeepAliveTimeout=60s）...</p>");
        body.append("<p style=\"color:#666\">探测前缓存: ").append(cacheStateText()).append("</p>");
        sleepUntil(t0 + gap * 1000000L);

        for (int i = 1; i <= probes; i++) {
            body.append(callRest(payload, respSize, "探测#" + i, srvSleep));
        }
        body.append("<p style=\"color:#666\">探测后缓存: ").append(cacheStateText()).append("</p>");
        body.append(callRest(payload, respSize, "恢复", srvSleep));
        return body.toString();
    }

    /**
     * 通用复现流程: 预热所有实例 → 空闲 gapMs → 探测 → 恢复。
     */
    @GetMapping("/RestCallServlet")
    public String restCall(@RequestParam(value = "size", required = false, defaultValue = "1024") int size,
                           @RequestParam(value = "respSize", required = false, defaultValue = "0") int respSize,
                           @RequestParam(value = "gapMs", required = false, defaultValue = "0") long gapMs,
                           @RequestParam(value = "calls", required = false, defaultValue = "0") int calls,
                           @RequestParam(value = "serverSleepMs", required = false, defaultValue = "10") long serverSleepMs)
            throws InterruptedException {
        int reqSize = clampSize(size);
        int respSizeClamped = Math.max(0, Math.min(respSize, MAX_SIZE));
        long gap = gapMs <= 0 ? DEFAULT_GAP_MS : clamp(gapMs, 0, MAX_SLEEP_MS, DEFAULT_GAP_MS);
        long srvSleep = clamp(serverSleepMs, 0, MAX_SLEEP_MS, DEFAULT_SLEEP_MS);
        int instanceCount = Math.max(1, discoveryClient.getInstances(SERVICE_ID).size());
        int probeCount = calls <= 0 ? instanceCount : (int) clamp(calls, 1, 100, instanceCount);

        String payload = buildPayload(reqSize);
        StringBuilder body = new StringBuilder();
        body.append("<p>实例数=").append(instanceCount).append(", 请求报文=").append(reqSize).append("B, 响应报文=")
                .append(respSizeClamped == 0 ? "同请求" : respSizeClamped + "B")
                .append(", 空闲等待 gapMs=").append(gap).append("ms, 探测次数=").append(probeCount)
                .append(", serverSleepMs=").append(srvSleep)
                .append("<br>（客户端缓存 nap=60s 来自服务端 Keep-Alive: timeout=60 响应头；"
                        + "服务端 KeepAliveTimeout=60s 空闲关闭）</p>");
        for (int i = 1; i <= instanceCount; i++) {
            body.append(callRest(payload, respSizeClamped, "预热#" + i, srvSleep));
        }
        body.append("<p>... 空闲等待 ").append(gap).append("ms（服务端将按 KeepAliveTimeout 关闭空闲连接）...</p>");
        Thread.sleep(gap);
        for (int i = 1; i <= probeCount; i++) {
            body.append(callRest(payload, respSizeClamped, "探测#" + i, srvSleep));
        }
        for (int i = 1; i <= instanceCount; i++) {
            body.append(callRest(payload, respSizeClamped, "恢复#" + i, srvSleep));
        }
        return page("tsf /RestCallServlet（RestTemplate + 负载均衡 + 重试拦截器）", body.toString());
    }

    /**
     * 经 @LoadBalanced RestTemplate 调用服务端 /api/rest，异常打印完整调用栈（复现现场报错）。
     * 页面行带发起时刻与耗时，便于与服务端日志/抓包对时间线。
     */
    private String callRest(String payload, int respSize, String stage, long serverSleepMs) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<String>(payload, headers);
        long start = System.nanoTime();
        String startTs = ts(start);
        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    "http://" + SERVICE_ID + "/api/rest?sleepMs=" + serverSleepMs + "&respSize=" + respSize,
                    entity, String.class);
            String respBody = resp.getBody();
            return "<p>" + stage + " [" + startTs + "]: code=" + resp.getStatusCodeValue()
                    + ", serverPort=" + extractPort(respBody)
                    + ", respBytes=" + (respBody == null ? 0 : respBody.length())
                    + ", cost=" + (System.nanoTime() - start) / 1000000 + "ms, serverSleepMs=" + serverSleepMs + "</p>";
        } catch (Exception e) {
            log.error("[rest] {} 调用失败（Unexpected end of file from server 复现）", stage, e);
            return "<p style=\"color:red\">" + stage + " [" + startTs + "]: <b>error</b> " + e
                    + ", cost=" + (System.nanoTime() - start) / 1000000 + "ms"
                    + "<br>（完整调用栈见客户端控制台日志）</p>";
        }
    }

    /** 构建指定字节数的 JSON 填充报文（ASCII，保证 Content-Length 精确） */
    private static String buildPayload(int size) {
        StringBuilder sb = new StringBuilder(Math.max(size, 32));
        sb.append("{\"type\":\"req\",\"data\":{\"k\":\"");
        int pad = Math.max(0, size - sb.length() - 2);
        for (int i = 0; i < pad; i++) {
            sb.append('x');
        }
        sb.append("\"}}");
        return sb.toString();
    }

    /** KeepAliveCache 快照文本（反射不可用则提示降级） */
    private static String cacheStateText() {
        List<KeepAliveInspector.ConnInfo> snapshot = KeepAliveInspector.snapshot();
        if (snapshot == null) {
            return "(反射读取 KeepAliveCache 不可用)";
        }
        if (snapshot.isEmpty()) {
            return "(缓存为空)";
        }
        StringBuilder sb = new StringBuilder();
        for (KeepAliveInspector.ConnInfo c : snapshot) {
            sb.append(c).append("<br>");
        }
        return sb.toString();
    }

    private static String extractPort(String respBody) {
        if (respBody == null) {
            return "-";
        }
        Matcher m = PORT_PATTERN.matcher(respBody);
        return m.find() ? m.group(1) : "-";
    }

    private static String ts(long nanoTime) {
        long now = System.currentTimeMillis() + (nanoTime - System.nanoTime()) / 1000000L;
        return new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date(now));
    }

    /** 解析 gapList(逗号分隔) 或 gapMs×cycles；均无则用默认值 */
    private static List<Long> parseGaps(String gapList, long defaultGap, int cycles) {
        List<Long> gaps = new ArrayList<Long>();
        if (gapList != null && !gapList.trim().isEmpty()) {
            for (String part : gapList.split(",")) {
                try {
                    gaps.add(clamp(Long.parseLong(part.trim()), 0, MAX_SLEEP_MS, defaultGap));
                } catch (NumberFormatException ignored) {
                    // 跳过非法项
                }
            }
        }
        if (gaps.isEmpty()) {
            int n = (int) clamp(cycles, 1, 50, 1);
            for (int i = 0; i < n; i++) {
                gaps.add(defaultGap);
            }
        }
        return gaps;
    }

    private static int clampSize(int size) {
        return (int) clamp(size, 1, MAX_SIZE, DEFAULT_SIZE);
    }

    /** 数值参数钳制: 超范围回退默认值 */
    private static long clamp(long v, long min, long max, long def) {
        return (v < min || v > max) ? def : v;
    }

    /** 精确睡眠到截止时刻（粗睡 + 细睡 + 自旋，毫秒级精度） */
    private static void sleepUntil(long deadlineNano) throws InterruptedException {
        long remain;
        while ((remain = deadlineNano - System.nanoTime()) > 0) {
            if (remain > 3000000L) {
                Thread.sleep(remain / 1000000L - 2);
            } else if (remain > 500000L) {
                Thread.sleep(1);
            }
            // 最后 0.5ms 自旋保精度
        }
    }

    private String page(String title, String body) {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        out.write("<html><body><h3>" + title + "</h3>");
        out.write(body);
        out.write("</body></html>");
        out.flush();
        return sw.toString();
    }
}
