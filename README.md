# 顽鹿运动 → 捷安特骑行 记录同步（多端）

将顽鹿运动（Onelap）的骑行 FIT 文件自动同步到捷安特骑行（Giant Ride Life）。

> [!CAUTION]
> 顽鹿运动官网在登录时做了签名认证，需在请求头中加入签名数据，加签名的方法已同步至本仓库。
> 后加的签名效验不知道是不是为了防止其他方式调用接口设计的，如果是，请联系本人删除公开的代码。

## 缘起

自行车买的是捷安特的，之前用手机当码表，记录用的是捷安特的 app。后面买了迈金的码表，个人感觉顽鹿运动做的运动记录分析没有捷安特的适合自己，好在顽鹿运动可以下载 fit 文件同步到捷安特骑行。久而久之，同步骑行记录变成了日常，感觉甚是乏味。好在自己是个程序员——乏味的事情，那就交给程序来做吧。

## 多端结构（monorepo）

| 目录 | 端 | 状态 |
|---|---|---|
| [`android/`](android/) | Android App（Kotlin + Jetpack Compose + Material 3） | **可用 v1.0.0**（[下载 APK](../../releases)） |
| [`desktop/`](desktop/) | 桌面/服务器端（Java 8 + Maven，fat-jar 常驻后台） | 可用（[详细文档](desktop/PROJECT_DOCUMENTATION.md)） |
| `windows/` / `ios/` | 未来计划 | 未开始 |

多端可同时运行：去重不依赖任何本地/共享数据库，而是以**捷安特服务端「已上传文件列表」为唯一事实源**，各端互不感知。

## Android App

从 [Releases](../../releases) 下载最新 APK 安装（Android 8.0 及以上）。

- **自动同步**：后台周期同步（间隔可调、可关闭、可限仅 Wi-Fi），也可随时手动触发；
- **同步历史**：会话与逐条记录明细，失败标红、可单条重试；
- **服务端去重**：与桌面端同时使用也不会重复上传；
- **隐私**：账号密码仅保存在设备本地（加密存储），App 只与顽鹿、捷安特官方服务器通信。

首次使用：进入「设置」，分别填入顽鹿与捷安特的账号密码，回到「历史」页点「立即同步」即可。

## 桌面/服务器端

Java 8 编写的常驻后台程序，适合挂在电脑或服务器上定时同步，构建与部署见 [desktop/PROJECT_DOCUMENTATION.md](desktop/PROJECT_DOCUMENTATION.md)。

## 共享文档

- [`docs/api/`](docs/api/) — 顽鹿 / 捷安特接口契约（各端实现的统一依据）
- [`docs/design/multi-client-sync.md`](docs/design/multi-client-sync.md) — 跨端去重设计

## 反馈

问题与建议请提 [Issue](../../issues)。
