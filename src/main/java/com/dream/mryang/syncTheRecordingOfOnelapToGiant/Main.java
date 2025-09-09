package com.dream.mryang.syncTheRecordingOfOnelapToGiant;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.ConfigManager;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.HttpClientUtil;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.TxtOperationUtil;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.http.Consts;
import org.apache.http.NameValuePair;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.message.BasicNameValuePair;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author yang
 * @since 2024/8/28
 **/
public class Main {

    /**
     * 加载配置文件
     */
    private static final Properties properties = ConfigManager.getProperties();

    public static void main(String[] args) throws SchedulerException {
        // 创建JobDetail实例
        JobDetail job = JobBuilder.newJob(TaskJob.class).build();

        // 创建Trigger实例，定义任务执行的时间规则
        Trigger trigger1 = TriggerBuilder.newTrigger()
                .startNow()
                .withSchedule(CronScheduleBuilder.cronSchedule(properties.getProperty("sync.cronone.expression")))
                .build();
        // 创建Trigger实例，定义任务执行的时间规则
        Trigger trigger2 = TriggerBuilder.newTrigger()
                .withSchedule(CronScheduleBuilder.cronSchedule(properties.getProperty("sync.crontwo.expression")))
                .build();

        // 获取Scheduler实例
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        // 将任务和多个触发器注册到调度器中
        Set<Trigger> triggers = new HashSet<>();
        triggers.add(trigger1);
        triggers.add(trigger2);
        scheduler.scheduleJob(job, triggers, true);
        // 启动调度器
        scheduler.start();

        // 立即执行一次任务
        scheduler.triggerJob(job.getKey());
    }

    // 定义任务类
    public static class TaskJob implements Job {
        @Override
        public void execute(JobExecutionContext jobExecutionContext) {
            try {
                System.out.println("当前时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                // 下载顽鹿运动fit文件
                ArrayList<String> fitFileNameList = downloadTheOnelapFitFile();
                System.out.println("【预计】同步数量：" + fitFileNameList.size());
                // fit文件同步到捷安特骑行
                if (CollectionUtils.isNotEmpty(fitFileNameList)) {
                    syncFitFilesToGiantBike(fitFileNameList);
                }
            } catch (Exception e) {
                System.out.println("发生异常：" + e.getMessage());
            }
            System.out.println("----------------分割线----------------");
        }
    }

    private static ArrayList<String> downloadTheOnelapFitFile() {
        // 顽鹿运动官网 在登录时做了签名认证
        // 需在请求头中加入签名数据
        // 后加的签名效验不知道是不是为了防止其他方式调用接口设计的，如果是，请联系本人删除公开的代码

        // 封装请求头数据
        // 随机16位字符串
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(16);
        // 获取当前时间戳(秒级)
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        // 封装加签数据
        // 只做登录，简单处理
        String sign = DigestUtils.md5Hex("account=" + properties.getProperty("onelap.account") + "&nonce=" + nonce + "&password=" + DigestUtils.md5Hex(properties.getProperty("onelap.password")) +
                "&timestamp=" + timestamp + "&key=" + "fe9f8382418fcdeb136461cac6acae7b");
        HashMap<String, String> headers = new HashMap<>();
        headers.put("nonce", nonce);
        headers.put("timestamp", timestamp);
        headers.put("sign", sign);

        // 调 顽鹿运动登录 接口，获取登录信息
        String loginReturnJsonString = HttpClientUtil.doPostJson("https://www.onelap.cn/api/login", "{\"account\":\"" + properties.getProperty("onelap.account") + "\",\"password\":\"" + DigestUtils.md5Hex(properties.getProperty("onelap.password")) + "\"}", null, null, headers);
        // 输出 登录信息 Json字符串
        System.out.println("调 顽鹿运动登录 接口响应值：" + loginReturnJsonString);
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
        String myActivitiesJsonString = HttpClientUtil.doGet("https://u.onelap.cn/analysis/list", null, cookie);
        // 解析 我的活动 Json字符串
        JSONObject myActivitiesData = JSONObject.parseObject(myActivitiesJsonString);
        // 获取 我的活动 列表数据
        JSONArray myActivities = myActivitiesData.getJSONArray("data");

        // 确认同步最近活动数量
        int endIndex = Math.min(myActivities.size(), Integer.parseInt(properties.getProperty("sync.recent.activity.count")));

        // 读取已同步文件
        ArrayList<String> list = TxtOperationUtil.readTxtFile(properties.getProperty("sync.fit.file.save.path"));
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
            File file = new File(properties.getProperty("onelap.fit.file.storage.directory") + fileKey);
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
        formParams.add(new BasicNameValuePair("username", properties.getProperty("giant.username")));
        formParams.add(new BasicNameValuePair("password", properties.getProperty("giant.password")));

        // 调 捷安特骑行登录 接口，获取登录信息
        String loginReturnJsonString = HttpClientUtil.doPostJson("https://ridelife.giant.com.cn/index.php/api/login", null, formParams, null, null);
        System.out.println("调 捷安特骑行登录 接口响应值：" + loginReturnJsonString);
        // 解析 登录信息 Json字符串
        JSONObject loginReturnData = JSONObject.parseObject(loginReturnJsonString);
        // 解析出登录token
        String userToken = loginReturnData.getString("user_token");

        // 封装捷安特骑行上传fit文件参数
        MultipartEntityBuilder multipartEntityBuilder = MultipartEntityBuilder.create();
        for (String fitFileName : fitFileNameList) {
            File file = new File(properties.getProperty("onelap.fit.file.storage.directory") + fitFileName);
            multipartEntityBuilder.addBinaryBody("files[]", file, ContentType.DEFAULT_BINARY, file.getName());
        }
        ContentType CONTENT_TYPE = ContentType.create("text/plain", Consts.UTF_8);
        multipartEntityBuilder.addPart("token", new StringBody(userToken, CONTENT_TYPE));
        multipartEntityBuilder.addPart("device", new StringBody("bike_computer", CONTENT_TYPE));
        multipartEntityBuilder.addPart("brand", new StringBody("onelap", CONTENT_TYPE));

        // 调用接口上传文件
        String respondJson = HttpClientUtil.doPostJson("https://ridelife.giant.com.cn/index.php/api/upload_fit", null, null, multipartEntityBuilder, null);
        // 输出响应
        System.out.println("调 捷安特上传文件 接口响应值：" + respondJson);

        // 解析 上传文件 Json字符串
        JSONObject respondJsonData = JSONObject.parseObject(respondJson);
        // 解析 上传文件 状态
        Integer status = respondJsonData.getInteger("status");
        if (status == 1) {
            // 存储同步文件记录
            TxtOperationUtil.writeTxtFile(properties.getProperty("sync.fit.file.save.path"), fitFileNameList);
            System.out.println("【完成】同步数量：" + fitFileNameList.size());
        } else {
            System.out.println("调用接口上传文件响应异常，异常信息：" + respondJson);
        }
    }
}