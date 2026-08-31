# 备份与恢复演练

## 服务端每日备份

`backup` 服务启动后立即备份，之后按 `BACKUP_INTERVAL_SECONDS`（默认 86400 秒）运行：

- `postgres-时间.dump.enc`：PostgreSQL custom-format 逻辑备份。
- `attachments-时间.tar.gz.enc`：附件目录归档。
- `backup-时间.sha256`：同一快照两份密文的 SHA-256 清单。

两份归档均使用独立 `BACKUP_PASSWORD`、AES-256-CBC、PBKDF2-SHA256 和 200,000 次迭代加密。文件以 `.partial` 写完后原子改名，权限受 `umask 077` 限制；校验清单同样原子发布。备份卷默认只在本机；生产环境应再复制到访问隔离的异地主机或对象存储，并设置生命周期策略。

本实现不自动删除旧备份，避免错误保留策略造成不可恢复的数据丢失。容量与保留周期由管理员明确管理。

## 校验

先列出卷中的明确文件名：

```bash
docker compose exec backup ls -lh /backups
docker compose exec backup sha256sum -c /backups/backup-YYYYMMDDTHHMMSSZ.sha256
docker compose --profile tools run --rm restore verify /backups/postgres-YYYYMMDDTHHMMSSZ.dump.enc
docker compose --profile tools run --rm restore verify /backups/attachments-YYYYMMDDTHHMMSSZ.tar.gz.enc
```

## 空库恢复演练

不要直接在生产库上做演练。建立隔离环境与空数据库，注入相同 `BACKUP_PASSWORD`，然后：

```bash
docker compose --profile tools run --rm -e CONFIRM_RESTORE=YES restore database /backups/postgres-YYYYMMDDTHHMMSSZ.dump.enc
docker compose --profile tools run --rm -e CONFIRM_RESTORE=YES restore attachments /backups/attachments-YYYYMMDDTHHMMSSZ.tar.gz.enc
```

`restore` 是一次性工具服务：备份卷只读、附件卷按恢复需要可写。数据库恢复使用 `pg_restore --exit-on-error`，预期目标是空数据库；附件恢复可能覆盖同名对象，因此要求显式 `CONFIRM_RESTORE=YES`。不要把恢复演练指向生产库。

恢复后核对：账本数、成员数、账单数、Posting 本位币合计、附件数量/哈希、账户余额与备份前校验值。随后启动 API 并验证 `/health`、登录、增量同步和附件下载。

## 手机 `.billbackup`

设置页可创建独立的密码保护备份包。包内包含清单、逐表 JSONL 和附件。恢复支持：

- 合并：按主键 upsert，保留未包含在包内的本地记录。
- 完整替换：先完成密码、格式和清单校验，再替换逻辑数据。

恢复密码不会保存。忘记密码时无法解密，这是设计上的安全边界。
