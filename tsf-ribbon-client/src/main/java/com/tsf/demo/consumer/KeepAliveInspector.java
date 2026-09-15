package com.tsf.demo.consumer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 反射读取 JDK HttpURLConnection 连接缓存（sun.net.www.http.KeepAliveCache）内部状态。
 *
 * 关键机制（毕昇 BiSheng 1.8.0_452，与现场 JDK 完全一致，javap 反汇编核实）：
 *  - KeepAliveCache.put() 缓存空闲连接时，保留时长 nap 优先取 HttpClient.keepAliveTimeout，
 *    该值由【服务端响应头 Keep-Alive: timeout=N】解析而来（parseHTTPHeader → HeaderParser.findInt）；
 *  - 服务端不回该头时才回退系统属性 http.keepAlive.time.server（默认 5s）；
 *  - BES 9.5.5.033 与 Tomcat 9.0.108 默认都回 "Keep-Alive: timeout=60"
 *    → 客户端缓存空闲连接恰好 60s，与服务端 KeepAliveTimeout=60s 精确对齐，
 *    空闲约 60s 的调用存在"复用服务端已/正在关闭连接"的竞态窗口（前案"5s 逐出"结论
 *    对该 JDK 版本不成立）。
 * 本工具把 nap / 空闲时长 / 连接本地端口暴露到测试页面，用于实证上述机制与连接复用判定。
 */
public final class KeepAliveInspector {

    /** 单条缓存连接的快照 */
    public static final class ConnInfo {
        public final String key;
        public final int napMs;
        public final int localPort;
        public final long idleAgeMs;
        public final boolean socketClosed;

        ConnInfo(String key, int napMs, int localPort, long idleAgeMs, boolean socketClosed) {
            this.key = key;
            this.napMs = napMs;
            this.localPort = localPort;
            this.idleAgeMs = idleAgeMs;
            this.socketClosed = socketClosed;
        }

        @Override
        public String toString() {
            return key + " nap=" + napMs + "ms localPort=" + localPort
                    + " idleAge=" + idleAgeMs + "ms socketClosed=" + socketClosed;
        }
    }

    private KeepAliveInspector() {
    }

    /** 读取当前 KeepAliveCache 全部条目快照；反射不可用时返回 null（调用方降级显示） */
    @SuppressWarnings("unchecked")
    public static List<ConnInfo> snapshot() {
        try {
            Class<?> httpClientClass = Class.forName("sun.net.www.http.HttpClient");
            Field kacField = httpClientClass.getDeclaredField("kac");
            kacField.setAccessible(true);
            Object kac = kacField.get(null);
            if (kac == null) {
                return new ArrayList<ConnInfo>();
            }
            Map<Object, Object> cache = (Map<Object, Object>) kac;
            List<ConnInfo> result = new ArrayList<ConnInfo>();
            long now = System.currentTimeMillis();
            for (Map.Entry<Object, Object> e : cache.entrySet()) {
                Object vector = e.getValue();
                Class<?> vectorClass = vector.getClass();
                Field napField = vectorClass.getDeclaredField("nap");
                napField.setAccessible(true);
                int nap = napField.getInt(vector);
                String key = String.valueOf(e.getKey());
                for (Object entry : (Iterable<Object>) vector) {
                    result.add(describeEntry(key, nap, entry, now));
                }
            }
            return result;
        } catch (Throwable t) {
            return null;
        }
    }

    private static ConnInfo describeEntry(String key, int nap, Object entry, long now) throws Exception {
        Class<?> entryClass = entry.getClass();
        Field hcField = entryClass.getDeclaredField("hc");
        hcField.setAccessible(true);
        Object httpClient = hcField.get(entry);
        Field idleField = entryClass.getDeclaredField("idleStartTime");
        idleField.setAccessible(true);
        long idleStart = idleField.getLong(entry);

        int localPort = -1;
        boolean closed = false;
        if (httpClient != null) {
            try {
                Field socketField = findField(httpClient.getClass(), "serverSocket");
                if (socketField != null) {
                    socketField.setAccessible(true);
                    Object socket = socketField.get(httpClient);
                    if (socket instanceof java.net.Socket) {
                        java.net.Socket s = (java.net.Socket) socket;
                        localPort = s.getLocalPort();
                        closed = s.isClosed();
                    }
                }
            } catch (Throwable ignored) {
                // socket 不可达时仅丢失端口信息，不影响其余字段
            }
        }
        long age = Math.max(0, now - idleStart);
        return new ConnInfo(key, nap, localPort, age, closed);
    }

    /** 沿类层次查找字段（serverSocket 声明在父类 sun.net.NetworkClient） */
    private static Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续向父类找
            }
        }
        return null;
    }
}
