package com.dream.mryang.syncTheRecordingOfOnelapToGiant.service;

import com.alibaba.fastjson2.JSONObject;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.db.SyncRecordDao;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.AuthFailedException;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.ConfigManager;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.HttpClientUtil;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.SyncConstants;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.TokenCache;
import org.apache.http.Consts;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class GiantBikeService {
    private static final Logger log = LoggerFactory.getLogger(GiantBikeService.class);

    /** token 缓存：仅首次登录，失效由 all_upload/upload 判定后续登重试。 */
    private final TokenCache tokenCache = new TokenCache(GiantBikeService::login);

    /** 登录换取 user_token。返回 null/空由 {@link TokenCache} 判为登录失败。 */
    private static String login() {
        List<NameValuePair> formParams = new ArrayList<>();
        formParams.add(new BasicNameValuePair("username", ConfigManager.getProperty("giant.username")));
        formParams.add(new BasicNameValuePair("password", ConfigManager.getProperty("giant.password")));
        String json = HttpClientUtil.doPost(SyncConstants.GIANT_LOGIN_URL,
                new UrlEncodedFormEntity(formParams, StandardCharsets.UTF_8), null);
        String userToken = JSONObject.parseObject(json).getString("user_token");
        if (userToken == null || userToken.isEmpty()) {
            log.warn("调 捷安特骑行登录 失败，响应：{}", json);
        } else {
            log.info("调 捷安特骑行登录 成功，已获取 user_token");
        }
        return userToken;
    }

    /**
     * 拉取捷安特已上传文件列表（多端去重事实源）。每次同步会话只调一次。
     * 响应异常由 {@link AllUploadSummary#parse} 判为认证失效 → 续登重试一次。
     */
    public AllUploadSummary fetchAllUploadSummary() {
        return tokenCache.withAuthRetry(token -> {
            List<NameValuePair> form = new ArrayList<>();
            form.add(new BasicNameValuePair("token", token));
            String json = HttpClientUtil.doPost(SyncConstants.GIANT_ALL_UPLOAD_URL,
                    new UrlEncodedFormEntity(form, StandardCharsets.UTF_8), null);
            // all_upload 可达数千条，只记条数不打全文
            AllUploadSummary summary = AllUploadSummary.parse(json);
            log.info("捷安特已上传文件 {} 个，其中处理失败 {} 个",
                    summary.getUploaded().size(), summary.getFailedProcess().size());
            return summary;
        });
    }

    /**
     * 整批上传本次下载的 FIT 文件到捷安特。上传 status 异常判为认证失效 → 续登重试一次；
     * 最终成功 markSynced、失败 markUploadFailed（记账语义不变，真实处理结果由下次会话 reconcile 确认）。
     */
    public void syncFitFilesToGiantBike(ArrayList<String> fitFileNameList) {
        if (fitFileNameList == null || fitFileNameList.isEmpty()) {
            return;
        }
        try {
            String respondJson = tokenCache.withAuthRetry(token -> uploadBatch(token, fitFileNameList));
            SyncRecordDao.markSynced(fitFileNameList);
            log.info("【完成】同步数量：{}，响应：{}", fitFileNameList.size(), respondJson);
        } catch (AuthFailedException e) {
            // 续登后仍认证失败：属流程级问题，本批记上传失败，下次会话自然重试
            log.error("捷安特上传认证持续失效，本批标记为上传失败", e);
            SyncRecordDao.markUploadFailed(fitFileNameList, "认证失效：" + e.getMessage());
        } catch (Exception e) {
            log.error("同步捷安特上传过程异常，本批标记为上传失败", e);
            SyncRecordDao.markUploadFailed(fitFileNameList, e.getMessage());
        }
    }

    /** 单次整批上传；status != 1 抛 AuthFailedException 以触发续登重试。 */
    private String uploadBatch(String userToken, ArrayList<String> fitFileNameList) {
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();
        for (String fitFileName : fitFileNameList) {
            File file = new File(ConfigManager.getProperty("onelap.fit.file.storage.directory") + fitFileName);
            builder.addBinaryBody("files[]", file, ContentType.DEFAULT_BINARY, file.getName());
        }
        ContentType textContentType = ContentType.create("text/plain", Consts.UTF_8);
        builder.addPart("token", new StringBody(userToken, textContentType));
        builder.addPart("device", new StringBody(SyncConstants.GIANT_DEVICE, textContentType));
        builder.addPart("brand", new StringBody(SyncConstants.GIANT_BRAND, textContentType));

        String respondJson = HttpClientUtil.doPost(SyncConstants.GIANT_UPLOAD_FIT_URL, builder.build(), null);
        log.info("调 捷安特上传文件 接口响应值：{}", respondJson);

        Integer status = JSONObject.parseObject(respondJson).getInteger("status");
        if (status == null || status != 1) {
            throw new AuthFailedException("捷安特上传响应 status=" + status + "，视为认证失效：" + respondJson);
        }
        return respondJson;
    }
}
