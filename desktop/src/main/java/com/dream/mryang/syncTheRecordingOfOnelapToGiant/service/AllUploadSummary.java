package com.dream.mryang.syncTheRecordingOfOnelapToGiant.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.AuthFailedException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 捷安特 all_upload 响应的解析结果，多端去重的事实源快照。
 * <p>
 * 契约见 docs/api/giant.md §3、去重规则见 docs/design/multi-client-sync.md。
 * <ul>
 *   <li>{@code uploaded}：服务端出现过的全部文件名（任意处理状态，去重匹配键）；</li>
 *   <li>{@code failedProcess}：已上传但全部处理非成功的文件 → 文件名到「状态: 消息」文案，供标红人工处理。</li>
 * </ul>
 */
public class AllUploadSummary {

    /** 服务端真实处理状态：成功 */
    public static final String STATUS_SUCCESS = "成功";

    private final Set<String> uploaded;
    private final Map<String, String> failedProcess;

    private AllUploadSummary(Set<String> uploaded, Map<String, String> failedProcess) {
        this.uploaded = uploaded;
        this.failedProcess = failedProcess;
    }

    public Set<String> getUploaded() {
        return uploaded;
    }

    public Map<String, String> getFailedProcess() {
        return failedProcess;
    }

    /**
     * 解析 all_upload 响应。根 {@code status != 1} 视为认证失效抛 {@link AuthFailedException}
     * （契约：响应 status 异常按 token 失效处理，触发续登重试）。
     */
    public static AllUploadSummary parse(String json) {
        JSONObject root = JSONObject.parseObject(json);
        Integer status = root.getInteger("status");
        if (status == null || status != 1) {
            throw new AuthFailedException("捷安特 all_upload 响应异常（status=" + status + "），视为认证失效：" + json);
        }

        JSONArray data = root.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            return new AllUploadSummary(Collections.emptySet(), Collections.emptyMap());
        }

        Set<String> uploaded = new LinkedHashSet<>();
        // 文件名 → 是否已有任一成功
        Map<String, Boolean> anySuccess = new LinkedHashMap<>();
        // 文件名 → 最后一条的失败文案（仅在无成功时有意义）
        Map<String, String> lastFailedText = new LinkedHashMap<>();

        for (int i = 0; i < data.size(); i++) {
            JSONObject item = data.getJSONObject(i);
            String file = item.getString("file");
            if (file == null || file.trim().isEmpty()) {
                continue;
            }
            uploaded.add(file);

            String recordStatus = item.getString("status");
            boolean success = STATUS_SUCCESS.equals(recordStatus);
            anySuccess.merge(file, success, Boolean::logicalOr);
            if (!success) {
                lastFailedText.put(file, buildFailedText(recordStatus, item.getString("msg")));
            }
        }

        Map<String, String> failedProcess = new LinkedHashMap<>();
        for (String file : uploaded) {
            if (!Boolean.TRUE.equals(anySuccess.get(file))) {
                failedProcess.put(file, lastFailedText.get(file));
            }
        }
        return new AllUploadSummary(uploaded, failedProcess);
    }

    /** 拼「状态: 消息」，空白部分省略（与 Android 端一致）。 */
    private static String buildFailedText(String status, String msg) {
        StringBuilder sb = new StringBuilder();
        if (status != null && !status.trim().isEmpty()) {
            sb.append(status);
        }
        if (msg != null && !msg.trim().isEmpty()) {
            if (sb.length() > 0) {
                sb.append(": ");
            }
            sb.append(msg);
        }
        return sb.toString();
    }
}
