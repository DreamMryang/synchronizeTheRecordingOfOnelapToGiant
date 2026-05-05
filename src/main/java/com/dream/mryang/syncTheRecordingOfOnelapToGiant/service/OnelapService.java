package com.dream.mryang.syncTheRecordingOfOnelapToGiant.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.ConfigManager;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.HttpClientUtil;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.SyncConstants;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.TxtOperationUtil;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.UUID;

public class OnelapService {
    private static final Logger log = LoggerFactory.getLogger(OnelapService.class);

    public ArrayList<String> downloadTheOnelapFitFile() {
        // 1. 登录
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(16);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        String account = ConfigManager.getProperty("onelap.account");
        String passwordParam = DigestUtils.md5Hex(ConfigManager.getProperty("onelap.password"));

        String sign = DigestUtils.md5Hex("account=" + account + "&nonce=" + nonce + "&password=" + passwordParam +
                "&timestamp=" + timestamp + "&key=" + ConfigManager.getProperty("onelap.sign.key"));

        HashMap<String, String> loginHeaders = new HashMap<>();
        loginHeaders.put("nonce", nonce);
        loginHeaders.put("timestamp", timestamp);
        loginHeaders.put("sign", sign);

        JSONObject loginReq = new JSONObject();
        loginReq.put("account", account);
        loginReq.put("password", passwordParam);

        String loginReturnJsonString = HttpClientUtil.doPost(SyncConstants.ONELAP_LOGIN_URL,
                new StringEntity(loginReq.toJSONString(), ContentType.APPLICATION_JSON), loginHeaders);
        log.info("调 顽鹿运动登录 接口响应值：{}", loginReturnJsonString);

        JSONObject loginReturnData = JSONObject.parseObject(loginReturnJsonString);
        JSONArray data = loginReturnData.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            throw new RuntimeException("顽鹿运动登录失败，响应数据为空：" + loginReturnJsonString);
        }
        JSONObject loginData = data.getJSONObject(0);
        String token = loginData.getString("token");

        // 2. 计算查询日期范围
        int syncDays = Integer.parseInt(ConfigManager.getProperty("sync.recent.days"));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String endDateStr = LocalDate.now().format(formatter);
        String startDateStr = LocalDate.now().minusDays(syncDays).format(formatter);

        HashMap<String, String> authHeaders = new HashMap<>();
        authHeaders.put("Authorization", token);

        // 3. 先调一次获取总记录数
        JSONObject listReq = new JSONObject();
        listReq.put("page", 1);
        listReq.put("limit", 20);
        listReq.put("start_date", startDateStr);
        listReq.put("end_date", endDateStr);

        String firstListJson = HttpClientUtil.doPost(SyncConstants.ONELAP_ACTIVITY_LIST_URL,
                new StringEntity(listReq.toJSONString(), ContentType.APPLICATION_JSON), authHeaders);
        log.info("调 顽鹿运动获取历史活动列表(首次) 接口响应值：{}", firstListJson);

        JSONObject pagination = JSONObject.parseObject(firstListJson).getJSONObject("data").getJSONObject("pagination");
        int total = pagination.getIntValue("total");
        log.info("顽鹿运动 {} 至 {} 共 {} 条活动记录", startDateStr, endDateStr, total);

        if (total == 0) {
            return new ArrayList<>();
        }

        // 4. 用 total 作为 limit 一次性取回全部数据
        listReq.put("limit", total);
        String allListJson = HttpClientUtil.doPost(SyncConstants.ONELAP_ACTIVITY_LIST_URL,
                new StringEntity(listReq.toJSONString(), ContentType.APPLICATION_JSON), authHeaders);
        log.info("调 顽鹿运动获取历史活动列表(全量) 接口响应值：{}", allListJson);

        JSONArray myActivities = JSONObject.parseObject(allListJson).getJSONObject("data").getJSONArray("list");
        if (myActivities == null || myActivities.isEmpty()) {
            return new ArrayList<>();
        }

        // 5. 逐条查询详情并下载未同步的文件
        ArrayList<String> alreadySynced = TxtOperationUtil.readTxtFile(ConfigManager.getProperty("sync.fit.file.save.path"));
        ArrayList<String> syncFileName = new ArrayList<>();

        for (int i = 0; i < myActivities.size(); i++) {
            JSONObject activity = myActivities.getJSONObject(i);
            String id = activity.getString("id");

            String detailJson = HttpClientUtil.doGet(SyncConstants.ONELAP_ACTIVITY_DETAIL_URL + id, null, authHeaders);

            JSONObject ridingRecord = JSONObject.parseObject(detailJson).getJSONObject("data").getJSONObject("ridingRecord");
            String fitUrl = ridingRecord.getString("fitUrl");

            if (fitUrl == null || fitUrl.isEmpty()) {
                log.warn("活动 id={} 的 fitUrl 为空，跳过", id);
                continue;
            }
            if (alreadySynced.contains(fitUrl)) {
                log.info("文件 {} 已同步，跳过", fitUrl);
                continue;
            }

            String fitUrlBase64 = Base64.getEncoder().encodeToString(fitUrl.getBytes(StandardCharsets.UTF_8));
            File file = new File(ConfigManager.getProperty("onelap.fit.file.storage.directory") + fitUrl);
            HttpClientUtil.downloadFile(SyncConstants.ONELAP_FIT_DOWNLOAD_URL + fitUrlBase64, authHeaders, file);
            syncFileName.add(fitUrl);
        }

        return syncFileName;
    }
}
