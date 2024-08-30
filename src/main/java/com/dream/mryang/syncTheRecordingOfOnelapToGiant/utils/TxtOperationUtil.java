package com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * @author yang
 * @since 2024/8/29
 */
public class TxtOperationUtil {
    /**
     * 根据文件路径读取文件
     *
     * @param filePath 文件路径
     */
    public static ArrayList<String> readTxtFile(String filePath) {
        try {
            // 返回值集合
            ArrayList<String> respondList = new ArrayList<>();
            // 文件流对象
            FileInputStream fileInputStream = new FileInputStream(filePath);
            // 文件读取流对象
            InputStreamReader inputStreamReader = new InputStreamReader(fileInputStream, StandardCharsets.UTF_8);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
            // 读取行数据中间变量
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                respondList.add(line);
            }
            // 关闭流
            fileInputStream.close();
            inputStreamReader.close();
            // 返回数据对象
            return respondList;
        } catch (Exception e) {
            throw new RuntimeException("读取txt文件异常，请检查后再试", e);
        }
    }

    /**
     * 写入文件
     *
     * @param filePath 文件路径
     * @param textList 文件行内容
     */
    public static void writeTxtFile(String filePath, List<String> textList) {
        try (RandomAccessFile raf = new RandomAccessFile(new File(filePath), "rw")) {
            byte[] originalContent = new byte[(int) raf.length()];
            // 存储原记录
            raf.read(originalContent);
            // 将指针移到首行
            raf.seek(0);
            for (String textString : textList) {
                raf.write(textString.getBytes());
                // 添加换行
                raf.write(("\n").getBytes());
            }
            raf.write(originalContent);
        } catch (Exception e) {
            throw new RuntimeException("数据写入txt文件异常，请检查后再试");
        }
    }
}