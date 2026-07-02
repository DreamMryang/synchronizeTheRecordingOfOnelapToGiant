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
                if (response.getStatusLine().getStatusCode() == 200) {
                    return EntityUtils.toString(response.getEntity(), "UTF-8");
                } else {
                    throw new RuntimeException("GET请求返回状态码异常：" + response.getStatusLine().getStatusCode() + "，URL：" + url);
                }
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
                byte[] data = EntityUtils.toByteArray(response.getEntity());
                try (FileOutputStream fos = new FileOutputStream(savePath)) {
                    fos.write(data);
                }
            }
        } catch (IOException e) {
            log.error("文件下载失败：{}", url, e);
            throw new RuntimeException("文件下载失败：" + url, e);
        }
    }
}
