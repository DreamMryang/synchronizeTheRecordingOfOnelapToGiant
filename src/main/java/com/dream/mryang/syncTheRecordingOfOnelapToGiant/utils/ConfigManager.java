package com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;

/**
 * 配置管理类
 */
public class ConfigManager {

    private static final Properties properties;

    static {
        properties = new Properties();
        try (InputStream input = ConfigManager.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (input == null) {
                throw new RuntimeException("无法找到配置文件config.properties");
            }
            properties.load(new InputStreamReader(input));
        } catch (IOException ex) {
            System.out.println("加载配置文件异常" + ex.getMessage());
        }
    }

    public static String getProperty(String key) {
        String property = properties.getProperty(key);
        if (StringUtils.isBlank(property)) {
            throw new RuntimeException("配置文件缺少属性：" + key);
        }
        return property;
    }
}