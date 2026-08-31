# 拾账 0.1.0 发布记录

## Android 安装包

- 包名：`com.billrecord.ledger`
- `versionCode`：1
- `versionName`：0.1.0
- 最低 Android：8.0（API 26）
- 目标 Android：API 36
- 交付 APK：`dist/bill-record-android-0.1.0.apk`
- 文件大小：59,985,203 字节
- APK SHA-256：`0363c353f6c7f0a21f9c2b71ad00bc62b53d0e417bdbbf6f47c09ae0b275e3d9`

`apksigner verify --verbose --print-certs` 已确认 APK 使用 v2、v3 签名，签名者为一把 4096 位 RSA 长期密钥：

- 证书主题：`CN=Bill Record, OU=Personal Distribution, O=Bill Record, C=CN`
- 证书 SHA-256：`0e7a65d09afd53c7233aaf9d6ba14a9c8e72253baa628185fc507a4a355661ff`

## 签名材料

签名材料位于仓库外，目录 ACL 仅允许当前 Windows 用户访问：

- `%USERPROFILE%\.billrecord-signing\billrecord-release.p12`
- `%USERPROFILE%\.billrecord-signing\signing-credential.clixml`

凭据文件使用 Windows DPAPI 加密，只能由当前 Windows 用户在当前安全上下文中解密。两份文件都必须离线备份；后续所有升级 APK 必须继续使用同一密钥。DPAPI 文件不能替代跨设备的安全离线密码备份。

## 验证结果

2026-08-24 已完成：

- shared/server/app JVM 单元测试、Android instrumentation 测试源码编译；
- Debug 与经过 R8/资源压缩的 Release 构建；
- 同步幂等、字段合并与冲突、拆分实体、设备绑定、查看者拒写、角色刷新、审计接口烟测；
- 700,000 字节附件的断点续传、偏移拒绝、下载长度与 SHA-256 校验；
- PostgreSQL 18 空库真实恢复，快照实体计数与版本合计一致；
- 附件归档真实恢复及哈希一致；
- Compose 容器全部删除并重建（保留具名卷）后登录、数据库记录和附件仍存在；
- PostgreSQL 无宿主机发布端口，API 与 PostgreSQL 健康检查通过。

Release 内置的初始服务器地址是 `https://ledger.example.com`，用户可在设置中改成自己的 HTTPS 私有云地址。

服务端独立部署包为 `dist/bill-record-server-0.1.0.zip`（97,176 字节），SHA-256 为 `be22dee1548f4cf0bf88a4a18f5f610108b832a7d8d428cc3119d38b0aa8cee0`。包内不包含 `.env`、密码、签名密钥、APK、构建缓存或测试数据库；统一校验清单位于 `dist/SHA256SUMS.txt`。
