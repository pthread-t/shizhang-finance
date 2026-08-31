# 部署、升级与 APK 签名

## 前置条件

- 一台具有公网域名的 Linux 主机，安装 Docker Engine 与 Compose v2。
- 域名 A/AAAA 记录指向主机，80/443 入站开放。
- 不需要向公网开放 PostgreSQL 端口；Compose 没有声明数据库 `ports`。

## 初始化

1. 复制 `.env.example` 为 `.env`。
2. 设置真实 `APP_DOMAIN`，并分别生成 PostgreSQL、JWT 和备份长随机密码。不要复用密码。
3. 执行 `docker compose config` 检查配置，再执行 `docker compose up -d --build`。
4. `docker compose ps` 中 PostgreSQL 与 API 应为 healthy。
5. 访问 `https://域名/health`，应返回 `{"status":"ok"}`。
6. Android 客户端保存该 HTTPS 地址，第一次登录勾选“初始化服务器”。记下只显示一次的恢复码。

Caddy 自动申请和续期证书。开发机使用 `APP_DOMAIN=localhost` 时会使用本地 CA；Android 真机默认不会信任该证书，真机联调建议使用受信域名。

## 持久化与网络

- `postgres_data` 挂载到 `/var/lib/postgresql`，并显式使用 `PGDATA=/var/lib/postgresql/18/docker`。
- `attachments_data`、`backup_data`、`caddy_data`、`caddy_config` 均为具名卷。
- `backend` 是 internal 网络；PostgreSQL 和 API 不映射宿主机端口。
- Caddy 同时连接 `edge` 和 `backend`，只有它映射 80/443。

重新创建容器不会删除具名卷。不要执行 `docker compose down -v`，除非已完成恢复演练且确实要永久清空环境。

## 升级

```bash
docker compose --profile tools build api backup restore
docker compose up -d
docker compose ps
```

API 启动时先执行 Flyway。升级前确认最新加密备份的 SHA-256 清单和解密结构均通过校验。若健康检查失败，保留容器和数据卷，先查看 `docker compose logs api postgres`。

成员角色和移除由服务端最终授权；客户端同步 `/memberships` 后立即切换为只读或已移除状态。角色变化也会通过 WebSocket 通知目标设备重新拉取权限。

## Android release 签名

签名密钥不得进入仓库。首次发布时使用 `keytool` 创建一把长期密钥并离线备份；所有后续版本必须使用同一密钥和固定包名，否则 Android 无法覆盖升级。

构建脚本从以下环境变量读取签名信息：

```text
ANDROID_KEYSTORE_PATH
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

设置后执行：

```bash
./gradlew :app:assembleRelease
```

签名 APK 位于 `app/build/outputs/apk/release/app-release.apk`。建议再用 Android SDK 的 `apksigner verify --verbose --print-certs` 验证证书指纹，并将该指纹、versionCode 与 APK SHA-256 记录到发布台账。

仓库不会生成默认 release 密码或提交 keystore，防止“拿到源码即拿到生产签名”的风险。
