package com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

public class HttpClientUtil {
    private static final Logger log = LoggerFactory.getLogger(HttpClientUtil.class);

    private static final RequestConfig REQUEST_CONFIG = RequestConfig.custom()
            .setConnectTimeout(10000)
            .setSocketTimeout(30000)
            .build();

    private static final PoolingHttpClientConnectionManager CM = new PoolingHttpClientConnectionManager();
    static {
        CM.setMaxTotal(50);
        CM.setDefaultMaxPerRoute(10);
    }

    private static final CloseableHttpClient HTTP_CLIENT = HttpClients.custom()
            .setConnectionManager(CM)
            .setDefaultRequestConfig(REQUEST_CONFIG)
            .build();

    public static String doPost(String url, HttpEntity entity, Map<String, String> headers) {
        HttpPost httpPost = new HttpPost(url);
        try {
            if (entity != null) {
                httpPost.setEntity(entity);
            }
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    httpPost.setHeader(entry.getKey(), entry.getValue());
                }
            }
            try (CloseableHttpResponse response = HTTP_CLIENT.execute(httpPost)) {
                return EntityUtils.toString(response.getEntity(), "UTF-8");
            }
        } catch (IOException e) {
            log.error("POST请求失败：{}", url, e);
            throw new RuntimeException("POST请求失败：" + url, e);
        }
    }

    public static String doGet(String url, Map<String, String> params, Map<String, String> headers) {
        try {
            URIBuilder builder = new URIBuilder(url);
            if (params != null) {
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    builder.addParameter(entry.getKey(), entry.getValue());
                }
            }
            URI uri = builder.build();
            HttpGet httpGet = new HttpGet(uri);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    httpGet.setHeader(entry.getKey(), entry.getValue());
                }
            }
            try (CloseableHttpResponse response = HTTP_CLIENT.execute(httpGet)) {
                int status = response.getStatusLine().getStatusCode();
                if (status == 200) {
                    return EntityUtils.toString(response.getEntity(), "UTF-8");
                }
                throwByStatus(status, url);
                return null; // 不可达（throwByStatus 必抛）
            }
        } catch (IOException | URISyntaxException e) {
            log.error("GET请求失败：{}", url, e);
            throw new RuntimeException("GET请求失败：" + url, e);
        }
    }

    public static void downloadFile(String url, Map<String, String> headers, File savePath) {
        HttpGet httpGet = new HttpGet(url);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                httpGet.setHeader(entry.getKey(), entry.getValue());
            }
        }
        try {
            try (CloseableHttpResponse response = HTTP_CLIENT.execute(httpGet)) {
                int status = response.getStatusLine().getStatusCode();
                // 必须先判状态码：否则 token 失效时会把错误页字节存成 FIT 文件（多端 token 缓存改造前的历史缺陷）
                if (status != 200) {
                    throwByStatus(status, url);
                }
                byte[] data = EntityUtils.toByteArray(response.getEntity());
                try (FileOutputStream fos = new FileOutputStream(savePath)) {
                    fos.write(data);
                }
            }
        } catch (AuthFailedException e) {
            throw e; // 认证失效原样上抛，供 TokenCache 续登重试
        } catch (IOException e) {
            log.error("文件下载失败：{}", url, e);
            throw new RuntimeException("文件下载失败：" + url, e);
        }
    }

    /** 非 200 响应按状态码分流：401/403 视为认证失效，其余为一般请求错误。 */
    private static void throwByStatus(int status, String url) {
        if (status == 401 || status == 403) {
            throw new AuthFailedException("请求返回 " + status + "，认证失效，URL：" + url);
        }
        throw new RuntimeException("请求返回状态码异常：" + status + "，URL：" + url);
    }
}
