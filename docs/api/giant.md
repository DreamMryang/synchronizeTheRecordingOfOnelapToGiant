# 捷安特骑行（Giant Ride Life）接口契约

> 各端实现的统一依据。逆向分析所得，非官方文档；接口行为如有变化以实际抓包为准，变更后请更新本文档。

## 1. 登录

```
POST https://ridelife.giant.com.cn/index.php/api/login
Content-Type: application/x-www-form-urlencoded
```

**表单参数：** `username`、`password`（明文）。

**响应：** `user_token` 为后续接口凭证。

## 2. 上传 FIT 文件（支持整批多文件）

```
POST https://ridelife.giant.com.cn/index.php/api/upload_fit
Content-Type: multipart/form-data
```

**multipart 字段：**

| 字段 | 值 |
|---|---|
| `files[]` | FIT 文件二进制（可重复，多文件整批上传） |
| `token` | 登录返回的 `user_token` |
| `device` | 固定 `bike_computer` |
| `brand` | 固定 `onelap` |

**响应：** `status == 1` 表示上传请求成功。注意：仅代表**文件上传成功**，不代表服务端已正确处理该文件（真实处理结果见接口 3）。

## 3. 已上传文件列表（全量，多端去重的事实源）

```
POST https://ridelife.giant.com.cn/index.php/api/all_upload
Content-Type: application/x-www-form-urlencoded
```

**表单参数：** 仅 `token`（登录返回的 `user_token`）。

**响应示例：**

```json
{
  "status": 1,
  "data": [
    {
      "msg": "success",
      "file": "MAGENE_C416_2026-06-18-17-51-08_768485_1781794983851.fit",
      "status": "成功",
      "time": "2026-06-19 00:00:11",
      "brand": "onelap",
      "device": "bike_computer"
    }
  ]
}
```

**字段说明：**

| 字段 | 说明 |
|---|---|
| `file` | 上传时的原始文件名（同步工具上传的文件名即顽鹿 `fitUrl`，**多端去重的匹配键**） |
| `status` | 服务端**真实处理状态**（如「成功」；非成功值表示文件已上传但处理失败） |
| `time` | 处理时间（观察到与上传时间不同，服务端处理疑似异步/批处理，如次日凌晨） |
| `msg` / `brand` / `device` | 处理消息 / 上传时携带的来源参数 |

**行为特性（重要）：**

- **无分页，一次返回当前账号全部历史记录**——记录可达数千条，调用频率与解析效率需控制（约定：每次同步会话只调用一次，详见[跨端同步设计](../design/multi-client-sync.md)）；
- **同一文件名可出现多条记录**（服务端允许重复上传），去重判断以「文件名是否出现过」为准，处理状态取「任意一条成功即成功」。

## 认证失效判定（各端 Token 缓存机制约定）

token 无官方有效期说明。各端应缓存 token（仅在无缓存时登录），业务请求返回 HTTP 401/403 或响应表明未认证（无 `user_token` / `status` 异常）时视为失效：清缓存 → 重新登录 → 重试原请求一次。
