# 贡献说明

这个项目同时包含 Android 客户端、共享领域模型和 Ktor 服务端。修改前请先确认问题属于哪一层，尽量让一次提交只解决一件事。

## 开发环境

- JDK 21
- Android SDK 36
- Docker Engine 与 Compose v2（需要运行服务端或恢复测试时）

Windows 用户可以运行 `./dev/android.ps1 setup` 准备隔离的 Android 工具目录。其他系统直接使用本机 JDK、Android SDK 和 Gradle Wrapper 即可。

## 提交改动

1. 从 `main` 创建分支。
2. 为行为变化补充测试，并运行与改动相关的 Gradle 任务。
3. 不提交真实账单、账号、服务器地址、访问令牌、签名材料或包含个人信息的截图。
4. Pull Request 中说明改动原因、验证方式和仍未覆盖的情况。界面变化请附脱敏截图。

完整测试矩阵见 [`docs/TESTING.md`](docs/TESTING.md)。同步协议或数据模型变化还应同步更新架构文档和 Room/Flyway 迁移。

## Mock 数据

测试数据必须是虚构的。可复用 `deploy/fixtures/full-year-v1.json`，也可以提交不含真实姓名、商户流水号、地址和联系方式的新 fixture。

## 许可

提交贡献时，请确认你有权提供这些内容，并同意按项目的 [Apache License 2.0](LICENSE) 授权。引入第三方代码时保留原许可与署名，在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 中说明来源。
