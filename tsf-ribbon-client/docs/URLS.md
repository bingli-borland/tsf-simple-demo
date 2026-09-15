# tsf-ribbon-client / tsf-ribbon-server

复现 SupportCase 2026年/63.ConnectionReset20260914 报错：
`java.net.SocketException: Unexpected end of file from server`，
调用栈与现场逐帧一致（spring-cloud-commons 3.1.8 / spring-cloud-loadbalancer 3.1.8 /
spring-retry 1.3.4 / spring-web 5.3.39 / femas-adaptor-tsf-springcloud，JDK 1.8）：

```
org.springframework.web.client.ResourceAccessException: I/O error on POST request for "http://tsf-ribbon-server/api/rest": Unexpected end of file from server; nested exception is java.net.SocketException: Unexpected end of file from server
Caused by: java.net.SocketException: Unexpected end of file from server
    at sun.net.www.http.HttpClient.parseHTTPHeader(HttpClient.java:873)
    ...
    at java.net.HttpURLConnection.getResponseCode(HttpURLConnection.java:480)
    at org.springframework.http.client.SimpleClientHttpResponse.getRawStatusCode(SimpleClientHttpResponse.java:55)
    at com.tencent.tsf.loadbalancer.TsfBlockingLoadBalancerClient.executeInternal(TsfBlockingLoadBalancerClient.java:129)
    at com.tencent.tsf.loadbalancer.TsfBlockingLoadBalancerClient.execute(TsfBlockingLoadBalancerClient.java:77)
    at org.springframework.cloud.client.loadbalancer.RetryLoadBalancerInterceptor.lambda$intercept$2(RetryLoadBalancerInterceptor.java:141)
    at org.springframework.retry.support.RetryTemplate.doExecute(RetryTemplate.java:329)
    at org.springframework.retry.support.RetryTemplate.execute(RetryTemplate.java:225)
    ...
```

> 完整验证报告/复验步骤/成品包见 `TSF/plan/impove/`（README.md 为索引）。

## 工程结构

- **tsf-ribbon-server**（默认端口 18091）：纯 REST 回显服务 `POST /api/rest`（无 POJO 序列化），
  响应 Content-Length 精确（非 chunked），内嵌 serverPort/reqBytes 便于观察命中实例。
- **tsf-ribbon-client**（默认端口 18093）：经 @LoadBalanced RestTemplate（JDK HttpURLConnection
  + spring-retry）调用服务端，提供浏览器测试入口（见下）。

## 报错原理（60s 空闲复用竞态）

1. 引擎默认 KeepAliveTimeout=60s（BES 9.5.5.033 的 server.config 与内嵌 Tomcat 9.0.108 均为 60s，
   且响应头广播 `Keep-Alive: timeout=60`）；
2. 毕昇 JDK 1.8.0_452 的 KeepAliveCache **nap 跟随服务端该响应头**（非旧认知的固定 5s）→
   客户端把空闲连接持有满 60 秒；
3. 服务端在"响应写完 + 60s + λ"（λ=引擎迟滞，恒正、约 56~391ms）关闭空闲连接；
4. 当网络往返延迟 + 请求写耗时 > λ（跨网段/广域网）时，空闲 ~60s 的调用存在毫秒级错误窗口：
   复用已被服务端关闭的连接 → 读状态行时收到 EOF → `Unexpected end of file from server`
   （个别时序为 Connection reset）；同机房快链路（RTT~1ms）结构性不发生；
5. POST 默认不自动重试（`retry-on-all-operations=false`），异常上抛打印完整栈；
   报错后下一次调用自动新建连接成功（自愈，与现场一致）。

## 启动

```bash
# 服务端（两种引擎，二选一或都起）
cd tsf-ribbon-server
mvn clean package -DskipTests              # 内嵌 Tomcat 9.0.108
java -jar target/tsf-ribbon-server.jar --server.port=18091

mvn clean package -Pbes -DskipTests         # BES 9.5.5.033 引擎（前置：BES 介质 installlib）
java -Dcom.bes.enterprise.licenseDir=license \
     -jar target/tsf-ribbon-server-bes.jar --server.port=18191
# license 文件必须命名 bes.lic.txt

# 客户端
cd ../tsf-ribbon-client
mvn clean package -DskipTests
java -jar target/tsf-ribbon-client.jar --server.port=18093 \
     --spring.cloud.discovery.client.simple.instances.tsf-ribbon-server[0].uri=http://<服务端>:18091
```

> yml 默认实例列表为 localhost:18091/18092，跨机部署时用上面的启动参数覆盖
> （`instances.tsf-ribbon-server[0].uri` 只配一个服务端可保证调用路径可预测）。
> 验证机上（108/109）用部署目录内 start-server.sh / start-client.sh 启动，见 impove/02-复验步骤。

## 测试入口 URL（客户端）

| URL | 功能 |
|---|---|
| `/` | 欢迎页：负载均衡实例列表 + 当前 KeepAliveCache 状态（nap 应=服务端广播的 timeout=60）+ 全部入口 |
| `/CacheStateServlet` | 实时打印 KeepAliveCache 内部状态：nap、空闲时长、连接本地端口（判定复用 vs 新建） |
| `/IdleReuseServlet` ★ | **60s 空闲复用专项（核心）**：每轮 预热 → 精确空闲 gap → 探测 → 恢复 |
| `/RestCallServlet` | 通用复现流程：预热所有实例 → 空闲 gapMs → 探测 → 恢复 |

### /IdleReuseServlet —— 核心验证入口

```
GET /IdleReuseServlet?size=<字节>&respSize=<字节>&gapList=<逗号分隔>&cycles=<轮数>&...
```

- 每轮输出：各步状态码/耗时/异常（含完整栈提示）、**调用前后 KeepAliveCache 快照**
  （本地端口不变=复用原连接，变=新建）、毫秒级时间戳（便于与服务端日志/抓包对时间线）；
- 空闲计时以"预热响应读完时刻"为 T0（与 KeepAliveCache idleStartTime 同源），粗睡+细睡+自旋
  对齐到毫秒；gap 与 nap(=60s) 同源对齐，扫描 [60−δ, 60) 即可观察复用窗口；
- 探测报错后自动执行"恢复"调用（预期立即成功）。

| 参数 | 默认 | 说明 |
|---|---|---|
| size | 1024 | 请求报文字节数（上限 4MB） |
| respSize | 0 | 响应报文字节数（0=与请求等长；上限 4MB） |
| gapMs | 59990 | 单一空闲时长（毫秒） |
| gapList | — | 逗号分隔逐轮扫描（优先于 gapMs），如 `59988,59984,59980` |
| cycles | 1 | 配合 gapMs 重复轮数（上限 50） |
| probeCount | 1 | 每轮探测次数（1~20） |
| jitterMs | 0 | 对 gap 加随机抖动 ±jitterMs |
| serverSleepMs | 10 | 服务端模拟业务耗时（不影响服务端关闭时刻，keep-alive 计时器从响应写完起算） |

示例：

```
http://localhost:18093/IdleReuseServlet?size=1024&gapList=60000,59999,59997,59990   # 快链路基线（预期全成功）
http://localhost:18093/IdleReuseServlet?size=1024&gapList=59988,59984,59980,59976   # netem 150ms 下（预期多轮报错）
```

### /RestCallServlet —— 通用流程

```
GET /RestCallServlet?size=<字节>&respSize=<字节>&gapMs=<毫秒>&calls=<次数>&serverSleepMs=<毫秒>
```

流程：预热（每实例 1 次）→ 空闲 gapMs → 探测 ×calls → 恢复 ×实例数。参数默认同上（gapMs 默认 59990）。

## 被调用的服务端接口（tsf-ribbon-server）

| 接口 | 说明 |
|---|---|
| `POST /api/rest` | 请求体任意字符串原样接收；响应 JSON 固定格式（serverPort/serverTime/reqBytes/pad），`respSize` 定长（0=与请求等长，上限 4MB），Content-Length 精确 |
| `GET /` | 存活探针：`tsf-ribbon-server port=<port> (/api/rest)` |

`sleepMs`（默认 10ms，上限 600s）模拟业务耗时——实测业务慢（哪怕 10s）不会使服务端提前关连接。

## 关键配置

- 实例列表：`spring.cloud.discovery.client.simple.instances.tsf-ribbon-server`（静态列表；
  部署时用启动参数覆盖为单实例）；接入 TSF 平台后可改走真实注册发现
- 自动重试：`spring.cloud.loadbalancer.retry.retry-on-all-operations`，默认 **false**
  （POST 失败上抛，复现现场栈）；验证缓解措施时启动参数设 true（失败自动换连接重试，实测可完全掩盖）
- 服务端 keep-alive：`ribbon-server.keep-alive-timeout-ms`，默认 **0 = 不干预**（用引擎默认 60s）。
  BES 形态显式调整用 `--server.bes.keep-alive-timeout=30`（**单位秒**，写 `5s` 启动失败）；
  Tomcat 用 `--server.tomcat.keep-alive-timeout=30s`；两引擎响应头均跟随变为 `timeout=30`

## 说明

Spring Cloud 2021 已移除 Netflix Ribbon，负载均衡由 spring-cloud-loadbalancer 承担
（BlockingLoadBalancerClient），TSF 适配层 TsfBlockingLoadBalancerClient 包装之，
调用栈形态与现场一致；现场 femas-adaptor-tsf-springcloud 为 1.0.27，本工程 BOM 传递 1.0.26，
类与行号基本一致。工程内未引入 com.bocsoft.dbsp 相关包（现场业务框架），对应栈帧不存在。
