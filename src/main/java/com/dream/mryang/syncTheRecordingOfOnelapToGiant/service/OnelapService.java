package com.dream.mryang.syncTheRecordingOfOnelapToGiant.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.ConfigManager;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.HttpClientUtil;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.SyncConstants;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.TxtOperationUtil;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class OnelapService {
    private static final Logger log = LoggerFactory.getLogger(OnelapService.class);

    public ArrayList<String> downloadTheOnelapFitFile() {
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(16);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        
        String account = ConfigManager.getProperty("onelap.account");
        String passwordParam = DigestUtils.md5Hex(ConfigManager.getProperty("onelap.password"));
        
        String sign = DigestUtils.md5Hex("account=" + account + "&nonce=" + nonce + "&password=" + passwordParam +
                "&timestamp=" + timestamp + "&key=" + ConfigManager.getProperty("onelap.sign.key"));
        
        HashMap<String, String> headers = new HashMap<>();
        headers.put("nonce", nonce);
        headers.put("timestamp", timestamp);
        headers.put("sign", sign);

        JSONObject loginReq = new JSONObject();
        loginReq.put("account", account);
        loginReq.put("password", passwordParam);

        String loginReturnJsonString = HttpClientUtil.doPostJson(SyncConstants.ONELAP_LOGIN_URL, loginReq.toJSONString(), null, null, headers);
        log.info("调 顽鹿运动登录 接口响应值：{}", loginReturnJsonString);

        JSONObject loginReturnData = JSONObject.parseObject(loginReturnJsonString);
        JSONArray data = loginReturnData.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            throw new RuntimeException("顽鹿运动登录失败，响应数据为空：" + loginReturnJsonString);
        }
        JSONObject loginData = data.getJSONObject(0);
        String token = loginData.getString("token");
        String refreshToken = loginData.getString("refresh_token");
        JSONObject loginUserInfoData = loginData.getJSONObject("userinfo");
        if (loginUserInfoData == null) {
            throw new RuntimeException("顽鹿运动登录响应中缺少 userinfo 字段：" + loginReturnJsonString);
        }
        String uid = loginUserInfoData.getString("uid");
        String cookie = "ouid=" + uid + "; XSRF-TOKEN=" + token + "; OTOKEN=" + refreshToken;

        String myActivitiesJsonString = HttpClientUtil.doGet(SyncConstants.ONELAP_ACTIVITY_URL, null, cookie);
        JSONObject myActivitiesData = JSONObject.parseObject(myActivitiesJsonString);
        JSONArray myActivities = myActivitiesData.getJSONArray("data");
        if (myActivities == null || myActivities.isEmpty()) {
            return new ArrayList<>();
        }

        int endIndex = Math.min(myActivities.size(), Integer.parseInt(ConfigManager.getProperty("sync.recent.activity.count")));

        ArrayList<String> list = TxtOperationUtil.readTxtFile(ConfigManager.getProperty("sync.fit.file.save.path"));
        ArrayList<String> syncFileName = new ArrayList<>();
        List<JSONObject> myActivityObjectList = myActivities.stream().limit(endIndex)
                .map(a -> (JSONObject) a)
                .filter(jsonObject -> !list.contains(jsonObject.getString("fileKey")))
                .collect(Collectors.toList());

        for (JSONObject jsonObject : myActivityObjectList) {
            String fileKey = jsonObject.getString("fileKey");
            String durl = jsonObject.getString("durl");
            File file = new File(ConfigManager.getProperty("onelap.fit.file.storage.directory") + fileKey);
            HttpClientUtil.downloadFile(durl, file);
            syncFileName.add(fileKey);
        }
        return syncFileName;
    }
}
