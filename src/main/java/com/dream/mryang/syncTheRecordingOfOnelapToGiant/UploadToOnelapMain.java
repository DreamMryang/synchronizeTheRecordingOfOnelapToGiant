package com.dream.mryang.syncTheRecordingOfOnelapToGiant;

import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.ConfigManager;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.HttpClientUtil;
import org.apache.http.Consts;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.StringBody;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author yang.yang
 * @since 2025/9/26
 **/
public class UploadToOnelapMain {

    /**
     * 将指定文件上传至顽鹿运动
     */
    public static void main(String[] args) throws InterruptedException {
        // todo 批量下载捷安特骑行fit同步文件

        // 存储需上传至顽鹿运动的文件路径
        Path directoryPath = Paths.get(ConfigManager.getProperty("upload.toonelap.path"));

        // 计数对象
        AtomicInteger count = new AtomicInteger(0);
        // 循环上传文件
        File directory = directoryPath.toFile();
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                System.out.println("获取文件数量：" + files.length);
                for (File file : files) {
                    if (file.isFile()) {
                        // 封装顽鹿运动上传fit文件参数
                        MultipartEntityBuilder multipartEntityBuilder = MultipartEntityBuilder.create();
                        multipartEntityBuilder.addBinaryBody("jilu", file, ContentType.DEFAULT_BINARY, file.getName());
                        ContentType CONTENT_TYPE = ContentType.create("text/plain", Consts.UTF_8);
                        // 封装token
                        multipartEntityBuilder.addPart("_token", new StringBody(ConfigManager.getProperty("upload.toonelap.token"), CONTENT_TYPE));

                        // 封装cookie
                        HashMap<String, String> headers = new HashMap<>();
                        headers.put("cookie", ConfigManager.getProperty("upload.toonelap.cookie"));

                        // 调用接口上传文件
                        String respondJson = HttpClientUtil.doPostJson("https://u.onelap.cn/upload/fit", null, null,
                                multipartEntityBuilder, headers);
                        System.out.println("计数：" + count.incrementAndGet() + "，上传响应值：" + respondJson);

                        // 上传响应生成的文件名称会重复，存在并发问题，需延时！！！
                        Thread.sleep(2000);
                    }
                }
            }
        }
    }
}
