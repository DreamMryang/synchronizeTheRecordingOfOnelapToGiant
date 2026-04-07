package com.dream.mryang.syncTheRecordingOfOnelapToGiant.utils;

public class SyncConstants {
    /** 顽鹿运动的登录接口地址 */
    public static final String ONELAP_LOGIN_URL = "https://www.onelap.cn/api/login";
    /** 顽鹿运动获取历史活动的接口地址 */
    public static final String ONELAP_ACTIVITY_URL = "https://u.onelap.cn/analysis/list";
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
