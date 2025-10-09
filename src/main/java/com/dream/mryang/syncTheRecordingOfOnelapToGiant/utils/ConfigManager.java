package com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;

/**
 * 配置管理类
 */
public class ConfigManager {

    private static Properties properties;

    public static synchronized Properties getProperties() {
        if (properties == null) {
            loadProperties();
        }
        return properties;
    }

    private static void loadProperties() {
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
}