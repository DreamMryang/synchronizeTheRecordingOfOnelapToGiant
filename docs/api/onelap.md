# 顽鹿运动（Onelap）接口契约

> 各端实现的统一依据。逆向分析所得，非官方文档；接口行为如有变化以实际抓包为准，变更后请更新本文档。

## 1. 登录（签名认证）

```
POST https://www.onelap.cn/api/login
Content-Type: application/json
```

**请求头（签名三件套）：**

| 头 | 说明 |
|---|---|
| `nonce` | UUID 去横线后取后 16 位 |
| `timestamp` | 秒级 Unix 时间戳 |
| `sign` | `md5("account=<账号>&nonce=<nonce>&password=<md5(明文密码)>&timestamp=<timestamp>&key=<签名密钥>")` |

签名密钥为固定值：`fe9f8382418fcdeb136461cac6acae7b`。

**请求体：**

```json
{ "account": "<账号>", "password": "<md5(明文密码)>" }
```

**响应：** `data[0].token` 为后续接口的凭证。`data` 为空/缺失表示登录失败。

## 2. 历史活动列表

```
POST https://u.onelap.cn/api/otm/ride_record/list
Content-Type: application/json
Authorization: <token>
```

**请求体：** `{ "page": 1, "limit": 20, "start_date": "yyyy-MM-dd", "end_date": "yyyy-MM-dd" }`

**响应：** `data.pagination.total` 为总条数，`data.list` 为活动数组（含活动 `id`）。

**`id` 为 24 位十六进制字符串**（形如 `"6a45f39dc323b737cc09a3a8"`，疑似 MongoDB ObjectId；2026-07 Android 端联调实测确认），各端解析时不得按数字处理。

惯用调用法：先 `limit=20` 调一次取 `total`，再以 `total` 为 `limit` 一次性取回全部。

## 3. 活动详情

```
GET https://u.onelap.cn/api/otm/ride_record/analysis/{活动id}
Authorization: <token>
```

**响应：** `data.ridingRecord.fitUrl` 为 FIT 文件标识（同时也是文件名，**全局唯一**，是多端去重的匹配键，见[跨端同步设计](../design/multi-client-sync.md)）。可能为空（无 FIT 文件的活动）。

## 4. 下载 FIT 文件

```
GET https://u.onelap.cn/api/otm/ride_record/analysis/fit_content/{base64(fitUrl)}
Authorization: <token>
```

**响应：** FIT 文件字节流。

## 5. 上传 FIT 文件（反向工具用，Android 版不迁移）

```
POST https://u.onelap.cn/upload/fit
Content-Type: multipart/form-data
Cookie: <从网页手工抓取>
```

**multipart 字段：** `jilu`（文件二进制）、`_token`（从网页手工抓取）。

注意：服务端按时间生成文件名存在并发冲突，连续上传需间隔约 2 秒。

## 认证失效判定（各端 Token 缓存机制约定）

token 无官方有效期说明。各端应缓存 token（仅在无缓存时登录），业务请求返回 HTTP 401/403 或响应中无有效 `data` 时视为失效：清缓存 → 重新登录 → 重试原请求一次。
