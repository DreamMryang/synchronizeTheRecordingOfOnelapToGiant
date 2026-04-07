package com.dream.mryang.syncTheRecordingOfOnelapToGiant.service;

import com.alibaba.fastjson2.JSONObject;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.ConfigManager;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.HttpClientUtil;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.SyncConstants;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.TxtOperationUtil;
import org.apache.http.Consts;
import org.apache.http.NameValuePair;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class GiantBikeService {
    private static final Logger log = LoggerFactory.getLogger(GiantBikeService.class);

    public void syncFitFilesToGiantBike(ArrayList<String> fitFileNameList) {
        if (fitFileNameList == null || fitFileNameList.isEmpty()) {
            return;
        }

        List<NameValuePair> formParams = new ArrayList<>();
        formParams.add(new BasicNameValuePair("username", ConfigManager.getProperty("giant.username")));
        formParams.add(new BasicNameValuePair("password", ConfigManager.getProperty("giant.password")));

        String loginReturnJsonString = HttpClientUtil.doPostJson(SyncConstants.GIANT_LOGIN_URL, null, formParams, null, null);
        log.info("调 捷安特骑行登录 接口响应值：{}", loginReturnJsonString);

        JSONObject loginReturnData = JSONObject.parseObject(loginReturnJsonString);
        String userToken = loginReturnData.getString("user_token");

        MultipartEntityBuilder multipartEntityBuilder = MultipartEntityBuilder.create();
        for (String fitFileName : fitFileNameList) {
            File file = new File(ConfigManager.getProperty("onelap.fit.file.storage.directory") + fitFileName);
            multipartEntityBuilder.addBinaryBody("files[]", file, ContentType.DEFAULT_BINARY, file.getName());
        }
        ContentType CONTENT_TYPE = ContentType.create("text/plain", Consts.UTF_8);
        multipartEntityBuilder.addPart("token", new StringBody(userToken, CONTENT_TYPE));
        multipartEntityBuilder.addPart("device", new StringBody(SyncConstants.GIANT_DEVICE, CONTENT_TYPE));
        multipartEntityBuilder.addPart("brand", new StringBody(SyncConstants.GIANT_BRAND, CONTENT_TYPE));

        String respondJson = HttpClientUtil.doPostJson(SyncConstants.GIANT_UPLOAD_FIT_URL, null, null, multipartEntityBuilder, null);
        log.info("调 捷安特上传文件 接口响应值：{}", respondJson);

        JSONObject respondJsonData = JSONObject.parseObject(respondJson);
        Integer status = respondJsonData.getInteger("status");
        if (status != null && status == 1) {
            TxtOperationUtil.writeTxtFile(ConfigManager.getProperty("sync.fit.file.save.path"), fitFileNameList);
            log.info("【完成】同步数量：{}", fitFileNameList.size());
        } else {
            log.error("调用接口上传文件响应异常，异常信息：{}", respondJson);
        }
    }
}
