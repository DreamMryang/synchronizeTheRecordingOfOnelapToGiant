package com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
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
import org.apache.http.util.EntityUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

/**
 * @author yang
 * @since 2024/8/29
 **/
public class HttpClientUtil {
    /**
     * 发送post请求，传递formData参数
     *
     * @param url        请求地址
     * @param formParams formData参数
     */
    public static String doPostJson(String url, String json, List<NameValuePair> formParams, MultipartEntityBuilder filesMultipartEntityBuilder) {
        // 创建Httpclient对象
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            // 创建Http Post请求
            HttpPost httpPost = new HttpPost(url);
            // 创建请求Json内容
            if (StringUtils.isNotBlank(json)) {
                StringEntity entity = new StringEntity(json, ContentType.APPLICATION_JSON);
                httpPost.setEntity(entity);
            }
            // 创建UrlEncodedFormEntity实例
            if (CollectionUtils.isNotEmpty(formParams)) {
                UrlEncodedFormEntity entity = new UrlEncodedFormEntity(formParams, "UTF-8");
                // 设置请求的实体为表单数据
                httpPost.setEntity(entity);
            }
            // 传递文件
            if (filesMultipartEntityBuilder != null) {
                HttpEntity entity = filesMultipartEntityBuilder.build();
                httpPost.setEntity(entity);
            }
            // 执行http请求
            CloseableHttpResponse response = httpClient.execute(httpPost);
            return EntityUtils.toString(response.getEntity(), "UTF-8");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 发送get请求
     *
     * @param url    请求地址
     * @param param  url参数
     * @param cookie cookie值
     */
    public static String doGet(String url, Map<String, String> param, String cookie) {
        // 创建Httpclient对象
        try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
            // 创建uri
            URIBuilder builder = new URIBuilder(url);
            if (param != null) {
                for (String key : param.keySet()) {
                    builder.addParameter(key, param.get(key));
                }
            }
            URI uri = builder.build();
            // 创建http GET请求
            HttpGet httpGet = new HttpGet(uri);
            // 封装cookie请求头
            if (StringUtils.isNotBlank(cookie)) {
                httpGet.setHeader("cookie", cookie);
            }
            // 执行请求
            CloseableHttpResponse response = httpclient.execute(httpGet);
            // 判断返回状态是否为200
            if (response.getStatusLine().getStatusCode() == 200) {
                return EntityUtils.toString(response.getEntity(), "UTF-8");
            } else {
                throw new RuntimeException("判断返回状态不为200，请排查问题");
            }
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 发送post请求，获取文件流并保存
     *
     * @param url      请求地址
     * @param savePath 保存文件全路径
     */
    public static void doPostJson(String url, File savePath) {
        // 创建Httpclient对象
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            // 创建Http Post请求
            HttpPost httpPost = new HttpPost(url);
            // 执行http请求
            CloseableHttpResponse response = httpClient.execute(httpPost);
            HttpEntity httpEntity = response.getEntity();
            byte[] data = EntityUtils.toByteArray(httpEntity);
            //存入磁盘
            try (FileOutputStream fos = new FileOutputStream(savePath)) {
                fos.write(data);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
