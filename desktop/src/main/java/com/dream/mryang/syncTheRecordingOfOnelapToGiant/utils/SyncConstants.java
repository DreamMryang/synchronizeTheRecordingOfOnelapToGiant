package com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils;

public class SyncConstants {
    /** 顽鹿运动的登录接口地址 */
    public static final String ONELAP_LOGIN_URL = "https://www.onelap.cn/api/login";
    /** 顽鹿运动获取历史活动列表的接口地址 */
    public static final String ONELAP_ACTIVITY_LIST_URL = "https://u.onelap.cn/api/otm/ride_record/list";
    /** 顽鹿运动获取活动详情的接口地址前缀（后接活动id） */
    public static final String ONELAP_ACTIVITY_DETAIL_URL = "https://u.onelap.cn/api/otm/ride_record/analysis/";
    /** 顽鹿运动下载FIT文件的接口地址前缀（后接fitUrl的Base64编码） */
    public static final String ONELAP_FIT_DOWNLOAD_URL = "https://u.onelap.cn/api/otm/ride_record/analysis/fit_content/";
    /** 顽鹿运动上传 FIT 文件的接口地址 */
    public static final String ONELAP_UPLOAD_URL = "https://u.onelap.cn/upload/fit";

    /** 捷安特骑行的登录接口地址 */
    public static final String GIANT_LOGIN_URL = "https://ridelife.giant.com.cn/index.php/api/login";
    /** 捷安特骑行同步/上传 FIT 文件的接口地址 */
    public static final String GIANT_UPLOAD_FIT_URL = "https://ridelife.giant.com.cn/index.php/api/upload_fit";

    /** 捷安特接口要求的设备类型参数 */
    public static final String GIANT_DEVICE = "bike_computer";
    /** 捷安特接口要求的品牌来源参数 */
    public static final String GIANT_BRAND = "onelap";
}
