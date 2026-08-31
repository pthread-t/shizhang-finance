# 架构与同步协议

## 离线优先数据流

```text
Compose UI → ViewModel → LedgerRepository → SQLCipher/Room
                                      └── 同事务写入 Outbox

WorkManager / 手动同步 → POST /api/v1/sync → PostgreSQL
          远端增量 ← 每账本 cursor ←──────────┘
                 ↓
              Room 更新 → Flow 自动刷新 UI
```

Room 是客户端唯一界面数据源。网络调用不会直接向界面返回账单；新增、修改和删除均先在本地完成。同步失败只改变同步状态，不能回滚用户刚完成的本地记账。

## AI 财务助手

AI 助手遵循“模型规划、本机执行、模型解释”的只读链路：模型先把问题转换为白名单 `FinanceQueryPlan`，客户端校验后使用 Room 查询并聚合，随后只把聚合结果发送给用户配置的模型。模型不能生成 SQL、ECharts Option 或账务写操作；原子账单、备注、附件与交易 ID 不出端。

模型配置与会话保存在 SQLCipher 中，不进入 Outbox；API Key 使用 Android 加密存储并且不进入逻辑备份。ECharts 运行文件随 APK 打包，WebView 只加载 `appassets` 本地来源；折线、柱状、环形及双维度热力图都由 Kotlin 白名单描述生成，点击事件只回传 Kotlin 预先生成的 `pointId`。下钻页用可保存的显式层级栈管理时间粒度、父子分类、原子账单列表和只读详情，系统返回、手势返回、顶部返回与面包屑共享同一出栈规则。

## 账务内核

`shared/Ledger.kt` 生成内部 Posting：

- 支出：资产账户减少，支出控制账户增加。
- 收入：资产账户增加，收入控制账户减少。
- 转账：来源减少、目标增加；跨币种按本位币保持恒等。
- 退款：资产账户增加、支出控制账户冲回。
- 余额调整：账户与权益调整控制账户成对变化。

每笔交易所有 `baseAmountMinor` 之和必须为 0。业务金额使用 `Long` 最小货币单位；汇率以十进制字符串保存，避免二进制浮点误差。

## 同步模型

`POST /api/v1/sync` 请求由 `deviceId`、`cursorByBook` 和最多 100 个 `operations` 组成。实体 ID 和 operation ID 均由客户端生成 UUID。

- `operationId` 在 `processed_operations` 中全局幂等。
- 每个实体有乐观 `version`；每个字段记录最后修改版本。
- 不同字段并发修改在服务端自动合并。
- 同字段并发以及删除/编辑进入客户端冲突中心。
- 删除保留在 `sync_entities` 中，作为永久 tombstone；不会因长期离线而复活。
- 服务端每次变更递增账本 `server_sequence`，客户端按账本游标拉取。
- WebSocket 只发送“账本已变化”提示，不承载账单正文；收到提示后仍调用 `/sync`。
- `/memberships` 返回当前用户仍有效的角色。客户端每次同步后刷新本地成员身份，查看者界面隐藏写入口，仓库层与服务端再分别校验一次。
- 附件以可恢复分块会话上传，偏移与 SHA-256 均校验；远端缺失附件按需下载并在本机复验长度和摘要。

所有变更写入 `audit_events`，并可由账本成员从只读审计接口查看。查看者无法提交变更；编辑者能处理账务实体；只有所有者能管理邀请和成员。

## 安全边界

- 本地数据库使用 SQLCipher，随机数据库口令由 Android Keystore AES/GCM 包装。
- 会话保存在 Android 加密存储；服务端密码使用 Argon2id。
- 访问令牌 15 分钟有效，刷新令牌每次使用后轮换；撤销设备后 JWT 验证立即拒绝该设备。
- 服务端可读：这不是端到端加密方案。TLS、数据库磁盘权限、主机加密和备份加密共同保护数据。
- API 日志只记录请求元信息，代码不主动记录金额、备注、令牌或无障碍节点原文。

## 数据库演进

PostgreSQL 使用 Flyway `server/src/main/resources/db/migration`。Room 开启 schema 导出至 `app/schemas`。发布后每次结构变化都必须增加新版本迁移，禁止依赖 destructive migration。
