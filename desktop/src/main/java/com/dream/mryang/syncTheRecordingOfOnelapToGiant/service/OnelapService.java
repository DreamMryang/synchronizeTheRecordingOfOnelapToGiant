package com.dream.mryang.syncTheRecordingOfOnelapToGiant.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.db.SyncRecordDao;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.*;
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
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class OnelapService {
    private static final Logger log = LoggerFactory.getLogger(OnelapService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 活动列表首次探测请求的分页大小；total 不超过它时首次响应即全量，无需二次调用。 */
    private static final int FIRST_PAGE_LIMIT = 20;

    /**
     * token 缓存：仅首次登录，失效由业务响应判定后续登重试。
     */
    private final TokenCache tokenCache = new TokenCache(OnelapService::login);

    /**
     * 顽鹿签名登录，取 data[0].token。返回 null/空由 {@link TokenCache} 判为登录失败。
     */
    private static String login() {
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

        String json = HttpClientUtil.doPost(SyncConstants.ONELAP_LOGIN_URL,
                new StringEntity(loginReq.toJSONString(), ContentType.APPLICATION_JSON), loginHeaders);
        JSONArray data = JSONObject.parseObject(json).getJSONArray("data");
        if (data == null || data.isEmpty()) {
            log.warn("调 顽鹿运动登录 失败，响应：{}", json);
            return null;
        }
        log.info("调 顽鹿运动登录 成功，已获取 token");
        return data.getJSONObject(0).getString("token");
    }

    private static HashMap<String, String> authHeader(String token) {
        HashMap<String, String> h = new HashMap<>();
        h.put("Authorization", token);
        return h;
    }

    /**
     * 下载顽鹿近 N 天中「捷安特服务端尚无记录」的活动 FIT 文件。
     * <p>
     * 去重仅依据服务端集合 {@code uploadedOnServer}（多端事实源），本地库不再参与判断。
     * 本地已下载/上传失败且文件仍在的直接复用，省一次下载。记账用 upsert 以支持自然重试。
     *
     * @param uploadedOnServer 捷安特 all_upload 返回的已上传文件名集合
     * @return 本次待上传的 fitUrl 列表（已在本地就绪）
     */
    public ArrayList<String> downloadTheOnelapFitFile(Set<String> uploadedOnServer) {
        String account = ConfigManager.getProperty("onelap.account");
        int syncDays = Integer.parseInt(ConfigManager.getProperty("sync.recent.days"));
        String endDateStr = LocalDate.now().format(DATE_FMT);
        String startDateStr = LocalDate.now().minusDays(syncDays).format(DATE_FMT);

        // 1. 活动列表（先取 total 再全量）
        JSONArray myActivities = tokenCache.withAuthRetry(token ->
                listActivities(token, startDateStr, endDateStr));
        if (myActivities == null || myActivities.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 逐条查详情 → 服务端集合去重 → 下载/复用
        ArrayList<String> syncFileName = new ArrayList<>();

        // 已有记录跳过 计数
        AtomicInteger existSkipCount = new AtomicInteger();
        // fitUrl为空跳过 计数
        AtomicInteger fitUrlCount = new AtomicInteger();

        for (int i = 0; i < myActivities.size(); i++) {
            String id = myActivities.getJSONObject(i).getString("id");
            String fitUrl = tokenCache.withAuthRetry(token -> fetchFitUrl(token, id));

            if (uploadedOnServer.contains(fitUrl)) {
                existSkipCount.incrementAndGet();
                continue;
            }
            if (fitUrl == null || fitUrl.isEmpty()) {
                fitUrlCount.incrementAndGet();
                continue;
            }

            File file = new File(ConfigManager.getProperty("onelap.fit.file.storage.directory") + fitUrl);
            try {
                if (canReuseLocal(fitUrl, file)) {
                    log.info("文件 {} 本地已就绪，复用不重复下载", fitUrl);
                } else {
                    tokenCache.withAuthRetry(token -> {
                        String base64 = Base64.getEncoder().encodeToString(fitUrl.getBytes(StandardCharsets.UTF_8));
                        HttpClientUtil.downloadFile(SyncConstants.ONELAP_FIT_DOWNLOAD_URL + base64, authHeader(token), file);
                        return null;
                    });
                }
                SyncRecordDao.upsertDownloaded(fitUrl, account, file.length());
                syncFileName.add(fitUrl);
            } catch (Exception e) {
                log.error("下载活动文件失败，跳过该条：{}", fitUrl, e);
                SyncRecordDao.upsertDownloadFailed(fitUrl, account, e.getMessage());
            }
        }
        log.info("捷安特服务端已有记录跳过数量：{}，fitUrl为空跳过数量：{}", existSkipCount.get(), fitUrlCount.get());
        return syncFileName;
    }

    /**
     * 本地记录为 DOWNLOADED / UPLOAD_FAILED 且文件存在且非空 → 可复用。
     */
    private boolean canReuseLocal(String fitUrl, File file) {
        String status = SyncRecordDao.findStatus(fitUrl);
        boolean reusableStatus = SyncRecordDao.STATUS_DOWNLOADED.equals(status)
                || SyncRecordDao.STATUS_UPLOAD_FAILED.equals(status);
        return reusableStatus && file.exists() && file.length() > 0;
    }

    /**
     * 活动列表：先 limit={@value FIRST_PAGE_LIMIT} 取 total；total 不超过首页时直接复用首次结果，
     * 否则再以 total 为 limit 全量取回。无 data 判为认证失效。
     */
    private JSONArray listActivities(String token, String startDateStr, String endDateStr) {
        JSONObject listReq = new JSONObject();
        listReq.put("page", 1);
        listReq.put("limit", FIRST_PAGE_LIMIT);
        listReq.put("start_date", startDateStr);
        listReq.put("end_date", endDateStr);

        String firstJson = HttpClientUtil.doPost(SyncConstants.ONELAP_ACTIVITY_LIST_URL,
                new StringEntity(listReq.toJSONString(), ContentType.APPLICATION_JSON), authHeader(token));
        JSONObject firstData = JSONObject.parseObject(firstJson).getJSONObject("data");
        if (firstData == null) {
            throw new AuthFailedException("顽鹿活动列表响应无 data，视为认证失效：" + firstJson);
        }
        int total = firstData.getJSONObject("pagination").getIntValue("total");
        log.info("调 顽鹿运动获取历史活动列表(首次) 成功，{} 至 {} 共 {} 条活动记录", startDateStr, endDateStr, total);
        if (total == 0) {
            return new JSONArray();
        }
        if (total <= FIRST_PAGE_LIMIT) {
            return firstData.getJSONArray("list");
        }

        listReq.put("limit", total);
        String allJson = HttpClientUtil.doPost(SyncConstants.ONELAP_ACTIVITY_LIST_URL,
                new StringEntity(listReq.toJSONString(), ContentType.APPLICATION_JSON), authHeader(token));
        JSONObject allData = JSONObject.parseObject(allJson).getJSONObject("data");
        if (allData == null) {
            throw new AuthFailedException("顽鹿活动列表(全量)响应无 data，视为认证失效：" + allJson);
        }
        JSONArray list = allData.getJSONArray("list");
        int listSize = list == null ? 0 : list.size();
        log.info("调 顽鹿运动获取历史活动列表(全量) 成功，返回 {} 条", listSize);
        if (listSize != total) {
            log.warn("顽鹿活动列表(全量)返回条数 {} 与 total {} 不一致，可能服务端对 limit 有上限，本次同步或有遗漏", listSize, total);
        }
        return list;
    }

    /**
     * 活动详情取 fitUrl。无 data 判为认证失效；fitUrl 可能为空（无 FIT 的活动）。
     */
    private String fetchFitUrl(String token, String id) {
        String detailJson = HttpClientUtil.doGet(SyncConstants.ONELAP_ACTIVITY_DETAIL_URL + id, null, authHeader(token));
        JSONObject data = JSONObject.parseObject(detailJson).getJSONObject("data");
        if (data == null) {
            throw new AuthFailedException("顽鹿活动详情响应无 data，视为认证失效：" + detailJson);
        }
        JSONObject ridingRecord = data.getJSONObject("ridingRecord");
        return ridingRecord == null ? null : ridingRecord.getString("fitUrl");
    }
}
