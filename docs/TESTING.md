# 测试与验收

## 自动测试

```bash
./gradlew :shared:test :server:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin
./gradlew :app:assembleDebug :server:installDist
docker compose config --quiet
```

当前自动测试覆盖复式恒等、收支/转账/退款/拆分与跨币种，文字解析、微信/支付宝支付页解析、CSV 引号/换行/编码基础行为、Room 1→2 迁移，以及服务端密码、令牌与字段冲突规则。

`./dev/android.ps1 test -FullMatrix` 会在 API 36 与 API 26 模拟器上运行设备测试，包括 Room 迁移、账务、标签关联删除、备份恢复和大数据集。需要第三方 API Key 的在线模型测试不属于默认测试集。

发布包还应单独检查包名、版本、ABI、签名、网络安全配置，以及 APK 中是否意外包含 fixture、凭据或本地调试地址。双设备同步和厂商后台限制必须使用真机验证。

部署后的增量同步烟测（需要已初始化的测试账号）可执行：

```powershell
$smokePassword = Read-Host "测试账号密码" -MaskInput
./deploy/smoke-sync.ps1 -BaseUrl https://localhost:18443 -Username smoke_owner -Password $smokePassword
```

它验证 operationId 幂等与复用拒绝、同字段冲突、不同字段合并、拆分实体、设备绑定、查看者拒写、成员角色刷新及审计接口。只对专用测试账本执行，因为会写入带 `sync smoke` 标记的测试实体。

## 发布前必做矩阵

- 飞行模式：新增、编辑、查询、统计、预算、提醒、CSV/XLSX 导出与完整备份。
- 两台真机：离线新增、相同字段并发、不同字段并发、删除/编辑、重复 operation ID、令牌轮换、设备撤销、成员移除、长期离线追赶。
- 数据量：至少 100,000 笔脱敏账单，验证分页、全文搜索、组合筛选和流式导出，核对行数与金额合计。
- 导入：真实脱敏微信、支付宝、通用 CSV/XLSX，覆盖退款、重复、缺列、GB18030、格式漂移和错误报告。
- 备份：从空数据库恢复 PostgreSQL 与附件，核对实体数、Posting 恒等、余额和 SHA-256。
- 容器：全新部署、restart、镜像更新、recreate 后数据仍在，宿主机不存在 5432 监听。
- Android 升级：同签名旧 APK 覆盖安装新 APK，本地数据库、附件、同步游标和会话仍在。
- 可用性：TalkBack、最大动态字体、深浅主题、小屏/平板、拒绝通知/相机/麦克风权限、后台受限。

无障碍支付解析依赖第三方页面结构，必须把每次适配器更新视作独立兼容性发布，不应把单次样本通过等同于长期可靠。
