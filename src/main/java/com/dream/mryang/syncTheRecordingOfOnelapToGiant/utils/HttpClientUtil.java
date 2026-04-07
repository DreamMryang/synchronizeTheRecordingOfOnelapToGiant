package com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author yang
 * @since 2024/8/29
 **/
public class HttpClientUtil {
    private static final Logger log = LoggerFactory.getLogger(HttpClientUtil.class);

    private static final RequestConfig REQUEST_CONFIG = RequestConfig.custom()
            .setConnectTimeout(10000)
            .setSocketTimeout(30000)
            .build();

    // 单例的连接池
    private static final PoolingHttpClientConnectionManager CM = new PoolingHttpClientConnectionManager();
    static {
        CM.setMaxTotal(50);
        CM.setDefaultMaxPerRoute(10);
    }

    private static final CloseableHttpClient HTTP_CLIENT = HttpClients.custom()
            .setConnectionManager(CM)
            .setDefaultRequestConfig(REQUEST_CONFIG)
            .build();

    public static String doPostJson(String url, String json, List<NameValuePair> formParams, MultipartEntityBuilder filesMultipartEntityBuilder, HashMap<String, String> headers) {
        HttpPost httpPost = new HttpPost(url);
        try {
            if (StringUtils.isNotBlank(json)) {
                httpPost.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));
            }
            if (CollectionUtils.isNotEmpty(formParams)) {
                httpPost.setEntity(new UrlEncodedFormEntity(formParams, "UTF-8"));
            }
            if (filesMultipartEntityBuilder != null) {
                httpPost.setEntity(filesMultipartEntityBuilder.build());
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

    public static String doGet(String url, Map<String, String> param, String cookie) {
        try {
            URIBuilder builder = new URIBuilder(url);
            if (param != null) {
                for (Map.Entry<String, String> entry : param.entrySet()) {
                    builder.addParameter(entry.getKey(), entry.getValue());
                }
            }
            URI uri = builder.build();
            HttpGet httpGet = new HttpGet(uri);
            if (StringUtils.isNotBlank(cookie)) {
                httpGet.setHeader("cookie", cookie);
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

    public static void downloadFile(String url, File savePath) {
        HttpPost httpPost = new HttpPost(url);
        try {
            try (CloseableHttpResponse response = HTTP_CLIENT.execute(httpPost)) {
                HttpEntity httpEntity = response.getEntity();
                byte[] data = EntityUtils.toByteArray(httpEntity);
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
