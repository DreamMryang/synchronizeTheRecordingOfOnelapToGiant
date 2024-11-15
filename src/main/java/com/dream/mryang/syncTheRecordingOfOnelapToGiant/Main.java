package com.dream.mryang.syncTheRecordingOfOnelapToGiant;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.HttpClientUtil;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.TxtOperationUtil;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.http.NameValuePair;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author yang
 * @since 2024/8/28
 **/
public class Main {
    private final static ContentType CONTENT_TYPE = ContentType.create(HTTP.PLAIN_TEXT_TYPE, HTTP.UTF_8);

    /**
     * 顽鹿运动账号
     */
    private static final String ONELAP_ACCOUNT = "";

    /**
     * 顽鹿运动密码
     */
    private static final String ONELAP_PASSWORD = "";

    /**
     * 捷安特骑行账号
     */
    private static final String GIANT_USERNAME = "";

    /**
     * 捷安特骑行密码
     */
    private static final String GIANT_PASSWORD = "";

    /**
     * 同步最近活动数量，约30天60次
     */
    private static final Integer SYNC_RECENT_ACTIVITY_COUNT = 60;

    /**
     * 顽鹿运动fit文件存储目录
     */
    private static final String ONELAP_FIT_FILE_STORAGE_DIRECOTRY = "/opt/yang/onelapFitFileStorageDirecotry/";

    /**
     * 已同步fit文件记录存储txt文件路径
     */
    private static final String SYNC_FIT_FILE_SAVE_FILE_PATH = "/opt/yang/onelapFitFileStorageDirecotry/syncFitFileSaveFile.txt";

    /**
     * 延迟初次执行的时间
     */
    private static final long INITIAL_DELAY = 0;

    /**
     * 两次执行之间的周期
     */
    private static final long PERIOD = 1;

    /**
     * 时间单位为 天
     */
    private static final TimeUnit TIME_UNIT = TimeUnit.DAYS;

    public static void main(String[] args) {
        // 定时任务执行体
        Runnable task = () -> {
            System.out.println("当前时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            // 下载顽鹿运动fit文件
            ArrayList<String> fitFileNameList = downloadTheOnelapFitFile();
            // fit文件同步到捷安特骑行
            if (CollectionUtils.isNotEmpty(fitFileNameList)) {
                syncFitFilesToGiantBike(fitFileNameList);
            }
            System.out.println("已完成同步数量：" + fitFileNameList.size());
            System.out.println("----------------分割线----------------");
        };

        // 创建定时任务执行线程池
        ScheduledExecutorService executorService = new ScheduledThreadPoolExecutor(1,
                new BasicThreadFactory.Builder().namingPattern("example-schedule-pool-%d")
                        .build());

        // 安排任务定期执行
        executorService.scheduleAtFixedRate(task, INITIAL_DELAY, PERIOD, TIME_UNIT);
    }

    private static ArrayList<String> downloadTheOnelapFitFile() {
        // 调 顽鹿运动登录 接口，获取登录信息
        String loginReturnJsonString = HttpClientUtil.doPostJson("https://www.onelap.cn/api/login", "{\"account\":\"" + ONELAP_ACCOUNT + "\",\"password\":\"" + DigestUtils.md5Hex(ONELAP_PASSWORD) + "\"}", null, null);
        // 输出 登录信息 Json字符串
        System.out.println(loginReturnJsonString);
        // 解析 登录信息 Json字符串
        JSONObject loginReturnData = JSONObject.parseObject(loginReturnJsonString);
        JSONArray data = loginReturnData.getJSONArray("data");
        JSONObject loginData = data.getJSONObject(0);
        // 解析出登录token1
        String token = loginData.getString("token");
        // 解析出登录token2
        String refreshToken = loginData.getString("refresh_token");
        // 解析出userinfo对象信息
        JSONObject loginUserInfoData = loginData.getJSONObject("userinfo");
        // 解析出uid
        String uid = loginUserInfoData.getString("uid");
        // 拼接cookie数据
        String cookie = "ouid=" + uid + "; " +
                "XSRF-TOKEN=" + token + "; " +
                "OTOKEN=" + refreshToken;
        // 调 我的活动 接口，获取活动记录
        String myActivitiesJsonString = HttpClientUtil.doGet("http://u.onelap.cn/analysis/list", null, cookie);
        // 解析 我的活动 Json字符串
        JSONObject myActivitiesData = JSONObject.parseObject(myActivitiesJsonString);
        // 获取 我的活动 列表数据
        JSONArray myActivities = myActivitiesData.getJSONArray("data");

        // 确认同步最近活动数量
        int endIndex;
        if (myActivities.size() >= SYNC_RECENT_ACTIVITY_COUNT) {
            endIndex = SYNC_RECENT_ACTIVITY_COUNT;
        } else {
            endIndex = myActivities.size();
        }

        // 读取已同步文件
        ArrayList<String> list = TxtOperationUtil.readTxtFile(SYNC_FIT_FILE_SAVE_FILE_PATH);
        // 同步文件名称
        ArrayList<String> syncFileName = new ArrayList<>();
        // 将已经同步的文件过滤
        List<Object> myActivitieObjectList = myActivities.stream().limit(endIndex).filter(a -> {
            // 获取 我的活动 数据
            JSONObject jsonObject = (JSONObject) JSONObject.toJSON(a);
            // 解析出文件名
            String fileKey = jsonObject.getString("fileKey");
            return !list.contains(fileKey);
        }).collect(Collectors.toList());
        // 循环下载文件
        for (Object myActivitieObject : myActivitieObjectList) {
            // 获取 我的活动 数据
            JSONObject jsonObject = (JSONObject) JSONObject.toJSON(myActivitieObject);
            // 解析出文件名
            String fileKey = jsonObject.getString("fileKey");
            // 解析出下载地址
            String durl = jsonObject.getString("durl");
            // 创建下载存储文件对象
            File file = new File(ONELAP_FIT_FILE_STORAGE_DIRECOTRY + fileKey);
            // 发送下载文件请求
            HttpClientUtil.doPostJson(durl, file);
            syncFileName.add(fileKey);
        }
        return syncFileName;
    }

    public static void syncFitFilesToGiantBike(ArrayList<String> fitFileNameList) {
        // 封装捷安特骑行登录参数
        // 创建NameValuePair列表用于存储表单数据
        List<NameValuePair> formParams = new ArrayList<>();
        formParams.add(new BasicNameValuePair("username", GIANT_USERNAME));
        formParams.add(new BasicNameValuePair("password", GIANT_PASSWORD));

        // 调 捷安特骑行登录 接口，获取登录信息
        String loginReturnJsonString = HttpClientUtil.doPostJson("https://ridelife.giant.com.cn/index.php/api/login", null, formParams, null);
        // 解析 登录信息 Json字符串
        JSONObject loginReturnData = JSONObject.parseObject(loginReturnJsonString);
        // 解析出登录token1
        String userToken = loginReturnData.getString("user_token");

        // 封装捷安特骑行上传fit文件参数
        MultipartEntityBuilder multipartEntityBuilder = MultipartEntityBuilder.create();
        for (String fitFileName : fitFileNameList) {
            File file = new File(ONELAP_FIT_FILE_STORAGE_DIRECOTRY + fitFileName);
            multipartEntityBuilder.addBinaryBody("files[]", file, ContentType.DEFAULT_BINARY, file.getName());
        }
        multipartEntityBuilder.addPart("token", new StringBody(userToken, CONTENT_TYPE));
        multipartEntityBuilder.addPart("device", new StringBody("bike_computer", CONTENT_TYPE));
        multipartEntityBuilder.addPart("brand", new StringBody("onelap", CONTENT_TYPE));

        // 调用接口上传文件
        String respondJson = HttpClientUtil.doPostJson("https://ridelife.giant.com.cn/index.php/api/upload_fit", null, null, multipartEntityBuilder);
        // 输出响应
        System.out.println(respondJson);

        // 解析 上传文件 Json字符串
        JSONObject respondJsonData = JSONObject.parseObject(respondJson);
        // 解析出登录token1
        Integer status = respondJsonData.getInteger("status");
        if (status == 1) {
            // 存储同步文件记录
            TxtOperationUtil.writeTxtFile(SYNC_FIT_FILE_SAVE_FILE_PATH, fitFileNameList);
        } else {
            throw new RuntimeException("调用接口上传文件响应异常，异常信息：" + respondJson);
        }
    }
}