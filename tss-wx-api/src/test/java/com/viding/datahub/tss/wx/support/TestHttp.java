package com.viding.datahub.tss.wx.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 契约测试用的真实 HTTP 客户端（JDK HttpClient）。
 *
 * <p>为什么不直接用 Spring 测试客户端：Spring Boot 4 已移除 {@code TestRestTemplate}，
 * 而本机 Maven 仓库中没有 {@code spring-boot-resttestclient}（且当前为离线构建）。
 * 用 JDK HttpClient 直连 {@code 127.0.0.1:<随机端口>} 反而最贴近「真实 HTTP 调用」的语义：
 * 请求真的走网络栈、响应头与状态码原样可见，不存在任何 Mock-in-process 的假象。
 */
public class TestHttp {

    /** HTTP 响应快照。 */
    public record Resp(int status, String body, Map<String, String> headers) {
        /** 按名取响应头（大小写不敏感）。 */
        public String header(String name) {
            return headers.get(name);
        }

        public boolean is2xx() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient client;
    private final String baseUrl;

    public TestHttp(int port) {
        this.baseUrl = "http://127.0.0.1:" + port;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Resp get(String path, String bearerToken) {
        return get(path, bearerToken, Map.of());
    }

    public Resp get(String path, String bearerToken, Map<String, String> extraHeaders) {
        return send("GET", path, null, bearerToken, extraHeaders);
    }

    public Resp post(String path, String jsonBody, String bearerToken) {
        return post(path, jsonBody, bearerToken, Map.of());
    }

    public Resp post(String path, String jsonBody, String bearerToken, Map<String, String> extraHeaders) {
        return send("POST", path, jsonBody, bearerToken, extraHeaders);
    }

    private Resp send(String method, String path, String jsonBody, String bearerToken,
                      Map<String, String> extraHeaders) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20));
        if (jsonBody == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        if (bearerToken != null && !bearerToken.isBlank()) {
            b.header("Authorization", "Bearer " + bearerToken);
        }
        extraHeaders.forEach(b::header);
        try {
            HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            resp.headers().map().forEach((k, v) -> {
                if (!v.isEmpty()) {
                    headers.put(k, v.get(0));
                }
            });
            return new Resp(resp.statusCode(), resp.body(), headers);
        } catch (IOException e) {
            throw new IllegalStateException("HTTP 调用失败: " + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP 调用被中断: " + method + " " + path, e);
        }
    }

    /** 构造请求头（便于传 If-None-Match 等）。 */
    public static Map<String, String> headers(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
