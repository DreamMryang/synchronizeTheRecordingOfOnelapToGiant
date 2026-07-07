package com.dream.mryang.syncTheRecordingOfOnelapToGiant.service;

import com.dream.mryang.syncTheRecordingOfOnelapToGiant.db.SyncRecordDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 同步去重/校正的纯逻辑与落库编排。
 * <p>
 * 以捷安特服务端 all_upload 为唯一事实源校正本机记账，规则见 docs/design/multi-client-sync.md §3。
 */
public class SyncLogic {

    private static final Logger log = LoggerFactory.getLogger(SyncLogic.class);

    private SyncLogic() {}

    /**
     * 依据服务端快照计算某本机记录应校正到的目标状态；无需变更时返回 null。
     * <ul>
     *   <li>仅 SYNCED / UPLOAD_FAILED 参与校正，其余状态返回 null；</li>
     *   <li>服务端处理失败（failedProcess 命中）→ PROCESS_FAILED；</li>
     *   <li>服务端已上传（uploaded 命中）→ SYNCED；</li>
     *   <li>服务端无记录 → null（保持现状，下次同步自然重试）；</li>
     *   <li>目标与现状相同 → null。</li>
     * </ul>
     */
    public static String reconcileTarget(String currentStatus, String fitUrl, AllUploadSummary summary) {
        if (!SyncRecordDao.STATUS_SYNCED.equals(currentStatus)
                && !SyncRecordDao.STATUS_UPLOAD_FAILED.equals(currentStatus)) {
            return null;
        }
        String target;
        if (summary.getFailedProcess().containsKey(fitUrl)) {
            target = SyncRecordDao.STATUS_PROCESS_FAILED;
        } else if (summary.getUploaded().contains(fitUrl)) {
            target = SyncRecordDao.STATUS_SYNCED;
        } else {
            return null; // 服务端无记录：保持现状，等下次同步自然重试
        }
        return target.equals(currentStatus) ? null : target;
    }

    /**
     * 遍历本机可校正记录，按服务端快照落库校正。
     * 转 PROCESS_FAILED 明确标红输出（改造项④：已上传但处理失败需人工处理），末尾汇总。
     */
    public static void reconcileLocal(AllUploadSummary summary) {
        Map<String, String> reconcilable = SyncRecordDao.findReconcilable();
        int flippedToSynced = 0;
        int flippedToProcessFailed = 0;
        for (Map.Entry<String, String> entry : reconcilable.entrySet()) {
            String fitUrl = entry.getKey();
            String current = entry.getValue();
            String target = reconcileTarget(current, fitUrl, summary);
            if (target == null) {
                continue;
            }
            if (SyncRecordDao.STATUS_PROCESS_FAILED.equals(target)) {
                String detail = summary.getFailedProcess().get(fitUrl);
                SyncRecordDao.updateStatus(fitUrl, target, detail);
                log.error("【处理失败】捷安特已收到但处理失败，需人工处理：{}（{}）", fitUrl, detail);
                flippedToProcessFailed++;
            } else {
                SyncRecordDao.updateStatus(fitUrl, target, null);
                log.info("校正本机记录：{} 由 {} → {}", fitUrl, current, target);
                flippedToSynced++;
            }
        }
        if (flippedToSynced > 0 || flippedToProcessFailed > 0) {
            log.info("reconcile 完成：校正为已同步 {} 条，标记处理失败 {} 条", flippedToSynced, flippedToProcessFailed);
        }
        int serverFailedTotal = summary.getFailedProcess().size();
        if (serverFailedTotal > 0) {
            log.warn("捷安特服务端当前共有 {} 个文件处理失败（含历史），如属本工具上传请人工排查", serverFailedTotal);
        }
    }
}
