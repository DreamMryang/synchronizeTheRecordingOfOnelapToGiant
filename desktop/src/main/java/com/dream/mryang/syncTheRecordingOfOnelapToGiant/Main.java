package com.dream.mryang.syncTheRecordingOfOnelapToGiant;

import com.dream.mryang.syncTheRecordingOfOnelapToGiant.db.SyncRecordDao;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.service.AllUploadSummary;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.service.GiantBikeService;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.service.OnelapService;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.service.SyncLogic;
import com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils.ConfigManager;
import org.apache.commons.collections4.CollectionUtils;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * @author yang
 * @since 2024/8/28
 **/
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws SchedulerException {
        // 初始化同步记录数据库（建表）
        SyncRecordDao.init();

        // 创建JobDetail实例
        JobDetail job = JobBuilder.newJob(TaskJob.class).build();

        // 创建Trigger实例，定义任务执行的时间规则
        Trigger trigger1 = TriggerBuilder.newTrigger()
                .startNow()
                .withSchedule(CronScheduleBuilder.cronSchedule(ConfigManager.getProperty("sync.cronone.expression")))
                .build();
        // 创建Trigger实例，定义任务执行的时间规则
        Trigger trigger2 = TriggerBuilder.newTrigger()
                .withSchedule(CronScheduleBuilder.cronSchedule(ConfigManager.getProperty("sync.crontwo.expression")))
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

        log.info("调度器已启动。");
        // 立即执行一次任务
        scheduler.triggerJob(job.getKey());
    }

    // 定义任务类
    @DisallowConcurrentExecution
    public static class TaskJob implements Job {
        private static final OnelapService onelapService = new OnelapService();
        private static final GiantBikeService giantBikeService = new GiantBikeService();

        @Override
        public void execute(JobExecutionContext jobExecutionContext) {
            try {
                log.info("当前时间：{}", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

                // 1. 拉取捷安特已上传列表（多端去重事实源，每次会话仅一次）
                AllUploadSummary summary = giantBikeService.fetchAllUploadSummary();

                // 2. 依据服务端结果校正本机记录（已同步 / 处理失败）
                SyncLogic.reconcileLocal(summary);

                // 3. 下载顽鹿中服务端尚无记录的活动 FIT 文件
                ArrayList<String> fitFileNameList = onelapService.downloadTheOnelapFitFile(summary.getUploaded());
                log.info("【预计】同步数量：{}", fitFileNameList.size());

                // 4. fit文件同步到捷安特骑行
                if (CollectionUtils.isNotEmpty(fitFileNameList)) {
                    giantBikeService.syncFitFilesToGiantBike(fitFileNameList);
                }
            } catch (Exception e) {
                log.error("发生异常：{}", e.getClass().getName(), e);
            }
            log.info("----------------分割线----------------");
        }
    }
}