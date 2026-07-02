# 顽鹿运动FIT文件同步至捷安特骑行项目文档

## 目录
1. [项目概述](#项目概述)
2. [技术架构](#技术架构)
3. [核心功能](#核心功能)
4. [系统设计](#系统设计)
5. [配置管理](#配置管理)
6. [部署指南](#部署指南)
7. [运维监控](#运维监控)
8. [安全分析](#安全分析)
9. [性能优化](#性能优化)
10. [问题与改进建议](#问题与改进建议)
11. [附录](#附录)

---

## 项目概述

### 项目简介
本项目是一个Java开发的自动化工具，用于将顽鹿运动（Onelap）平台的骑行记录（FIT文件）自动同步到捷安特骑行（Giant）平台。该项目解决了本人在两个不同骑行平台间手动同步数据的繁琐操作问题。

### 项目背景
- **开发者背景**：本人拥有捷安特品牌自行车，早期使用捷安特官方APP记录骑行数据
- **需求转变**：后期购买了迈金码表，发现顽鹿运动的数据分析功能更适合个人需求
- **数据迁移痛点**：已有部分历史数据存储在捷安特平台，无法直接迁移到顽鹿运动
- **解决方案**：利用顽鹿运动支持导出FIT文件的功能，开发自动化同步工具

### 核心价值
- ✅ **自动化同步**：无需人工干预，定时自动完成数据同步
- ✅ **防重复机制**：通过SQLite中`fit_url`唯一键避免重复同步相同文件
- ✅ **双向支持**：支持Onelap→Giant正向同步和本地文件上传到Onelap的反向功能
- ✅ **轻量化设计**：使用嵌入式SQLite（sqlite-jdbc）做状态管理，无需外部数据库服务

---

## 技术架构

### 技术栈选型
| 组件 | 版本 | 用途 |
|------|------|------|
| Java | 8 | 核心开发语言 |
| Maven | 3.x | 项目构建和依赖管理 |
| Quartz Scheduler | 2.3.2 | 定时任务调度 |
| Apache HttpClient (httpmime) | 4.5.14 | HTTP客户端请求 |
| Fastjson2 | 2.0.43 | JSON数据解析 |
| SQLite JDBC (xerial) | 3.45.3.0 | 嵌入式同步记录数据库 |
| Logback | 1.2.13 | 日志输出（控制台+按天滚动文件） |
| Apache Commons Lang3 | 3.18.0 | 字符串和通用工具类 |
| Apache Commons Collections4 | 4.4 | 集合操作工具类 |
| JUnit | 4.13.1 | 单元测试 |

### 项目结构
```
synchronizeTheRecordingOfOnelapToGiant/
├── src/
│   ├── main/
│   │   ├── java/com/dream/mryang/syncTheRecordingOfOnelapToGiant/
│   │   │   ├── Main.java                          # 主程序入口和定时任务
│   │   │   ├── UploadToOnelapMain.java            # 反向上传工具
│   │   │   ├── service/                           # 业务服务包
│   │   │   │   ├── OnelapService.java             # 顽鹿运动：登录/列表/详情/下载
│   │   │   │   └── GiantBikeService.java          # 捷安特骑行：登录/批量上传
│   │   │   ├── db/
│   │   │   │   └── SyncRecordDao.java             # SQLite同步记录数据访问层
│   │   │   └── utils/                             # 工具类包
│   │   │       ├── ConfigManager.java             # 配置管理器
│   │   │       ├── HttpClientUtil.java            # HTTP客户端工具（连接池+超时）
│   │   │       └── SyncConstants.java             # 外部接口地址等常量
│   │   └── resources/
│   │       ├── config.properties                  # 配置文件
│   │       └── logback.xml                        # 日志配置
│   └── test/
│       └── java/.../db/SyncRecordDaoTest.java     # SyncRecordDao单元测试
├── docs/                                          # 设计文档（SQLite同步记录设计等）
├── target/                                        # 编译输出目录
├── pom.xml                                        # Maven配置文件
├── README.md                                      # 项目说明文档
└── PROJECT_DOCUMENTATION.md                       # 项目完整文档
```

### 架构设计
```
┌─────────────────────────────────────────────────────────────────┐
│                        应用层 (Application Layer)                │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐    ┌──────────────────┐    ┌────────────────┐ │
│  │   Main      │◄──►│ TaskJob (Quartz) │    │ UploadToOnelap │ │
│  │  (主程序)   │    │   (定时任务)     │    │   (反向上传)   │ │
│  └─────────────┘    └──────────────────┘    └────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                        服务层 (Service Layer)                    │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────────┐    ┌──────────────────┐    ┌────────────┐ │
│  │  OnelapService   │    │ GiantBikeService │    │SyncRecordDao│ │
│  │ (下载FIT文件)    │    │ (批量上传FIT)    │    │(SQLite记录)│ │
│  └──────────────────┘    └──────────────────┘    └────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                        工具层 (Utility Layer)                    │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────────┐    ┌──────────────────┐    ┌────────────┐ │
│  │ HttpClientUtil   │    │  SyncConstants   │    │ConfigManager│ │
│  │ (HTTP客户端)     │    │ (接口地址常量)   │    │ (配置管理) │ │
│  └──────────────────┘    └──────────────────┘    └────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                        外部系统 (External Systems)               │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────────┐                           ┌──────────────┐ │
│  │  顽鹿运动平台    │◄─────────────────────────►│ 捷安特平台   │ │
│  │ (Onelap)        │    RESTful APIs           │ (Giant)      │ │
│  │  • 本人认证     │                           │  • 本人认证  │ │
│  │  • 活动列表     │                           │  • 文件上传  │ │
│  │  • 文件下载     │                           │              │ │
│  └──────────────────┘                           └──────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

---

## 核心功能

### 1. 定时同步功能
- 使用Quartz调度器实现双Cron表达式定时任务
- 支持灵活的时间调度配置
- 程序启动时立即执行一次同步任务

### 2. 顽鹿运动集成
- 实现登录认证和签名机制
- 获取本人活动列表
- 下载FIT文件到本地存储

### 3. 捷安特骑行集成
- 本人登录认证
- 批量上传FIT文件
- 响应状态验证

### 4. 重复同步防护与生命周期记录
- 使用SQLite单表`sync_record`记录，`fit_url`唯一键做去重
- 记录完整状态：`DOWNLOADED` / `SYNCED` / `UPLOAD_FAILED` / `DOWNLOAD_FAILED`
- 凡`fit_url`已有任意记录（含失败态）即跳过，失败不自动重试，需人工介入
- 下载阶段单条失败只记录、不中断整个任务

### 5. 反向上传功能
- 支持将本地FIT文件上传到顽鹿运动
- 处理上传并发问题
- 提供上传进度反馈

---

## 系统设计

### 核心组件详解

#### 1. 主程序类 (Main.java)
**功能职责：**
- 启动时初始化SQLite同步记录库（`SyncRecordDao.init()`建库建表）
- 初始化Quartz调度器，配置双Cron表达式定时任务（`@DisallowConcurrentExecution`禁止并发执行）
- 任务体串联`OnelapService`下载与`GiantBikeService`上传

**关键特性：**
```java
// 双定时器配置
Trigger trigger1 = TriggerBuilder.newTrigger()
    .withSchedule(CronScheduleBuilder.cronSchedule("0 0 0/6 * * ?"))  // 每6小时执行
    .build();
    
Trigger trigger2 = TriggerBuilder.newTrigger()
    .withSchedule(CronScheduleBuilder.cronSchedule("0 0/15 8-9 * * ?"))  // 早高峰时段密集执行
    .build();
```

#### 2. 配置管理器 (ConfigManager.java)
**设计理念：**
- 静态初始化块加载配置文件
- 运行时验证配置完整性
- 统一的配置访问接口

#### 3. HTTP客户端工具 (HttpClientUtil.java)
**设计要点：**
- 单例`CloseableHttpClient` + `PoolingHttpClientConnectionManager`连接池（maxTotal=50，每路由10）
- 统一超时配置：连接10秒、socket 30秒
- 提供`doPost`（接收任意`HttpEntity`：JSON/表单/multipart）、`doGet`、`downloadFile`三类方法

#### 4. 同步记录数据访问层 (SyncRecordDao.java)
**设计要点：**
- SQLite常驻单连接 + WAL模式，所有公开方法`synchronized`保证线程安全
- 单表`sync_record`，`fit_url`唯一键做去重
- 提供`init`/`findAllFitUrls`/`insertDownloaded`/`markDownloadFailed`/`markSynced`/`markUploadFailed`等方法
- 详细设计见 `docs/superpowers/specs/2026-06-03-sqlite-sync-record-design.md`

### 业务流程详解

#### 正向同步流程 (Onelap → Giant)
```mermaid
graph TD
    A[程序启动] --> B[初始化Quartz调度器]
    B --> C[注册双定时任务]
    C --> D[立即执行首次同步]
    D --> E[等待定时触发]
    
    E --> F[顽鹿运动登录]
    F --> G[获取活动列表]
    G --> H[过滤已同步文件]
    H --> I[下载新的FIT文件]
    I --> J[捷安特骑行登录]
    J --> K[上传FIT文件]
    K --> L[记录同步状态]
    L --> M[等待下次执行]
```

#### 详细步骤说明

##### 步骤1：顽鹿运动登录认证
```java
// 签名计算（签名密钥从配置文件 onelap.sign.key 读取）
String sign = DigestUtils.md5Hex(
    "account=" + account +
    "&nonce=" + nonce +
    "&password=" + passwordParam +
    "&timestamp=" + timestamp +
    "&key=" + ConfigManager.getProperty("onelap.sign.key")
);

// 请求头封装
headers.put("nonce", nonce);
headers.put("timestamp", timestamp);
headers.put("sign", sign);
```

##### 步骤2：活动列表获取与过滤
```java
// 按日期范围查询最近 sync.recent.days 天的活动：先查一次拿 total，
// 再用 total 作为 limit 一次性取回全部列表

// 用 SQLite 中全部已记录的 fit_url 做去重（含失败态记录，一律跳过）
Set<String> alreadySynced = SyncRecordDao.findAllFitUrls();
if (alreadySynced.contains(fitUrl)) {
    continue;
}
```

##### 步骤3：文件下载与存储
```java
// 携带 Authorization 头下载FIT文件；fitUrl 需 Base64 编码后拼接到下载地址
HttpClientUtil.downloadFile(SyncConstants.ONELAP_FIT_DOWNLOAD_URL + fitUrlBase64, authHeaders, file);
// 成功记 DOWNLOADED，失败记 DOWNLOAD_FAILED 并继续处理下一条
SyncRecordDao.insertDownloaded(fitUrl, account, file.length());
```

##### 步骤4：捷安特平台上传
```java
// 多文件批量上传
MultipartEntityBuilder builder = MultipartEntityBuilder.create();
for (String fileName : fileNames) {
    File file = new File(storageDirectory + fileName);
    builder.addBinaryBody("files[]", file, ContentType.DEFAULT_BINARY, fileName);
}
```

---

## 配置管理

### 配置文件详解 (config.properties)

#### 日常同步配置
```properties
# 顽鹿运动账号信息
onelap.account=your_account
onelap.password=your_password

# 捷安特骑行账号信息  
giant.username=your_username
giant.password=your_password

# 顽鹿运动接口签名密钥（固定值）
onelap.sign.key=fe9f8382418fcdeb136461cac6acae7b

# 同步策略配置
sync.recent.days=30                     # 同步最近30天的活动记录
onelap.fit.file.storage.directory=/path/to/storage/
sync.db.path=/path/to/sync_record.db    # SQLite同步记录库文件路径

# 定时任务配置
sync.cronone.expression=0 0 0/6 * * ?   # 每6小时执行
sync.crontwo.expression=0 0/15 8-9 * * ? # 早8-9点每15分钟执行

# 日志文件存储路径（被logback.xml引用）
log.file.path=/path/to/logs/
```

#### 反向上传配置
```properties
# 上传目标路径
upload.toonelap.path=/path/to/local/files/

# 顽鹿运动认证信息（页面获取）
upload.toonelap.token=page_token_value
upload.toonelap.cookie=page_cookie_value
```

---

## 部署指南

### 环境要求
- **操作系统**：Windows/Linux/macOS
- **Java版本**：JDK 8 或更高版本
- **磁盘空间**：根据同步频率预留足够存储空间
- **网络环境**：能够访问顽鹿运动和捷安特骑行API

### 部署步骤

#### 1. 环境准备
```bash
# 确保已安装Java 8+
java -version

# 确保已安装Maven
mvn -version
```

#### 2. 配置文件设置
编辑 `src/main/resources/config.properties`：
```properties
# 顽鹿运动账号（必填）
onelap.account=你的顽鹿运动账号
onelap.password=你的顽鹿运动密码

# 捷安特骑行账号（必填）
giant.username=你的捷安特账号
giant.password=你的捷安特密码

# 存储路径配置（根据系统调整）
onelap.fit.file.storage.directory=/path/to/storage/
sync.db.path=/path/to/sync_record.db
log.file.path=/path/to/logs/
```

注意：配置文件位于`src/main/resources/`，打包后固化在jar内，修改配置需重新打包。

#### 3. 构建运行
```bash
# 编译打包
mvn clean package

# 运行程序
java -jar target/synchronizeTheRecordingOfOnelapToGiant-1.0.0.jar
```

### 目录结构规划
```
/sync-app/
├── bin/                    # 可执行JAR文件
├── logs/                   # 日志文件目录
├── data/                   # 数据存储目录
│   ├── sync_record.db      # SQLite同步记录库（程序自动创建）
│   └── onelapFitFileStorageDirecotry/
│       └── *.fit           # 下载的FIT文件
└── backup/                 # 备份目录
```

---

## 运维监控

### 健康检查脚本
```bash
#!/bin/bash
# 检查进程是否运行
if pgrep -f "syncTheRecordingOfOnelapToGiant" > /dev/null; then
    echo "应用运行正常"
else
    echo "应用异常停止，尝试重启..."
    # 重启逻辑...
fi

# 检查磁盘空间
df -h /path/to/data/ | awk 'NR==2 {if($5+0 > 80) print "磁盘空间不足"}'
```

### 常见问题诊断

#### 1. 配置文件加载失败
```bash
# 检查配置文件路径
ls -la /path/to/config.properties
# 验证文件权限
chmod 644 config.properties
```

#### 2. 网络连接问题
```bash
# 测试API连通性
curl -I https://www.onelap.cn/api/login
curl -I https://ridelife.giant.com.cn/index.php/api/login
```

#### 3. 文件权限问题
```bash
# 检查存储目录权限
ls -ld /path/to/storage/
chmod 755 /path/to/storage/
```

---

## 安全分析

### 当前安全措施

#### 1. 签名认证机制
- 顽鹿运动登录采用签名认证
- 签名包含随机nonce、时间戳等防重放攻击要素
- 签名密钥已配置化（`onelap.sign.key`，接口要求的固定值）

#### 2. 配置隔离
- 敏感信息集中存储在配置文件中
- 仓库中账号密码字段留空，本地填入真实凭据后注意不要提交
- 运行时验证配置完整性（缺失或空值直接抛异常）

### 已识别安全风险

| 风险类型 | 当前状态 | 建议改进 |
|----------|----------|----------|
| MD5算法使用 | ⚠️ 存在风险（接口协议要求） | 受限于对方接口，暂无法更换 |
| 明文密码存储 | ⚠️ 存在风险 | 建议加密存储 |
| 敏感信息日志 | ⚠️ 存在风险 | 生产环境应脱敏 |

### 安全改进建议

#### 短期改进（高优先级）
1. **密码加密存储**：使用AES等加密算法存储敏感信息
2. **日志脱敏处理**：敏感信息输出时进行脱敏处理

#### 中长期规划
1. 集成配置中心（如Apollo、Nacos）
2. 实现密钥轮换机制
3. 添加HTTPS证书验证
4. 建立安全审计日志

---

## 性能优化

### 已落地的优化

#### 1. 网络请求
- `HttpClientUtil`使用单例连接池（maxTotal=50，每路由10）
- 统一超时设置：连接10秒、socket 30秒

#### 2. 同步记录存储
- 已由TXT文件迁移到SQLite：`fit_url`唯一索引去重（替代原O(n)线性扫描），写入具备事务原子性

#### 3. 并发控制
- `TaskJob`已加`@DisallowConcurrentExecution`禁止Quartz并发执行
- `SyncRecordDao`所有公开方法`synchronized`，单连接+WAL模式

### 遗留优化方向
- 文件下载仍为串行处理，量大时可考虑并行化
- 反向上传工具中固定2秒延时规避服务端并发问题，较为粗糙

---

## 问题与改进建议

### 已发现问题汇总

#### 一、安全性问题
1. **硬编码签名密钥**：签名密钥`fe9f8382418fcdeb136461cac6acae7b`硬编码在源代码中
2. **MD5哈希算法使用**：存在碰撞漏洞风险
3. **明文密码存储**：配置文件中密码明文存储
4. **敏感信息日志输出**：完整API响应输出到控制台

#### 二、代码质量问题
1. **方法命名与实际功能不符**：HttpClientUtil.doPostJson处理多种内容类型
2. **异常处理不完善**：仅打印异常信息，缺乏错误恢复机制
3. **资源管理问题**：HTTP连接未完全关闭（缺少try-with-resources）
4. **代码重复**：相同逻辑在多处重复出现

#### 三、功能逻辑问题
1. **文件下载缺少认证**：下载请求未传递认证信息
2. **文件写入逻辑可能重复**：未实现去重机制
3. **并发问题**：Quartz作业可能并发执行
4. **上传响应处理简单**：仅检查status==1，处理不够完善

#### 四、配置管理问题
1. **跨平台路径问题**：配置文件中混合Windows和Linux路径格式
2. **缺少配置验证**：配置属性为空时抛出运行时异常
3. **配置项命名不一致**：命名风格不统一

#### 五、性能与可靠性问题
1. **网络请求无超时设置**：可能导致线程长时间阻塞
2. **文件操作无缓冲策略**：大文件处理性能较差
3. **硬编码延时**：固定2秒延时处理并发问题

#### 六、扩展性与维护性问题
1. **硬编码API端点**：API变更需要修改代码
2. **缺乏监控和告警**：无任务执行统计和异常告警
3. **测试覆盖率不足**：缺少单元测试和集成测试
4. **文档缺失**：API接口变更记录和运维文档不完整

### 改进建议

#### 短期建议（高优先级）
1. **移除硬编码签名密钥**：将密钥移至配置文件，支持动态更新
2. **增强异常处理**：实现重试机制，添加详细错误日志
3. **修复资源泄漏**：使用try-with-resources确保HTTP响应正确关闭
4. **添加配置验证**：启动时验证所有必需配置，提供清晰错误信息
5. **改进文件下载认证**：下载FIT文件时传递必要的认证信息

#### 中期建议（中优先级）
1. **重构HTTP客户端**：分离不同内容类型的处理方法，添加连接池配置
2. **增强安全性**：配置文件中密码加密存储，敏感信息日志脱敏
3. **改进文件同步逻辑**：实现原子性文件操作，添加文件去重机制
4. **添加监控和日志**：结构化日志输出，关键指标监控

#### 长期建议（低优先级）
1. **架构优化**：考虑使用数据库替代TXT文件记录，支持多人同步
2. **测试体系建设**：添加单元测试和集成测试，实现持续集成
3. **部署和运维改进**：容器化部署，配置中心集成

---

## 附录

### API接口文档

#### 顽鹿运动相关接口
| 接口 | 地址 | 方法 | 用途 |
|------|------|------|------|
| 本人登录 | https://www.onelap.cn/api/login | POST | 获取认证令牌 |
| 活动列表 | https://u.onelap.cn/analysis/list | GET | 获取骑行活动列表 |
| 文件下载 | 从活动列表获取 | POST | 下载FIT文件 |

#### 捷安特骑行相关接口
| 接口 | 地址 | 方法 | 用途 |
|------|------|------|------|
| 本人登录 | https://ridelife.giant.com.cn/index.php/api/login | POST | 获取本人令牌 |
| 文件上传 | https://ridelife.giant.com.cn/index.php/api/upload_fit | POST | 上传FIT文件 |

### 常用命令参考

#### Maven构建命令
```bash
# 清理并编译
mvn clean compile

# 打包可执行JAR
mvn package

# 运行程序
java -jar target/syncTheRecordingOfOnelapToGiant.jar
```

#### Git操作命令
```bash
# 查看提交历史
git log --oneline -10

# 查看文件变更
git diff HEAD~1 HEAD

# 创建发布标签
git tag -a v1.0.0 -m "Release version 1.0.0"
git push origin v1.0.0
```

### 参考资料
- [Quartz Scheduler官方文档](http://www.quartz-scheduler.org/documentation/)
- [Apache HttpClient本人指南](https://hc.apache.org/httpcomponents-client-ga/)
- [FastJSON使用手册](https://github.com/alibaba/fastjson)
- [Cron表达式在线生成器](https://www.freeformatter.com/cron-expression-generator-quartz.html)

---

**文档版本**：v1.0  
**最后更新**：2026年3月  
**维护者**：yang.yang  
**项目状态**：稳定运行中