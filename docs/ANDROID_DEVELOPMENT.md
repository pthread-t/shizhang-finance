# Android 电脑端调试

默认情况下，工具安装在 `%LOCALAPPDATA%\BillRecordAndroid`。也可以通过 `-ToolsRoot` 参数或 `BILL_RECORD_TOOLS_ROOT` 环境变量指定其他目录。脚本只修改自身进程的环境变量。

```powershell
.\dev\android.ps1 doctor
.\dev\android.ps1 setup
.\dev\android.ps1 run -Backend local
.\dev\android.ps1 logs
.\dev\android.ps1 test
.\dev\android.ps1 test -FullMatrix
.\dev\android.ps1 build-staging -Version 0.1.2-rc1
.\dev\android.ps1 stop
```

`run -Backend local` 会启动独立的 `bill-record-local` Compose 项目，通过 `adb reverse` 访问仅监听 `127.0.0.1:18080` 的开发服务。远程测试可以传入 `-Backend staging -StagingServerUrl https://你的测试域名`。

脚本不会清空 App 数据、AVD 或 Docker 数据卷。若 `doctor` 报告模拟器硬件加速不可用，应先在 Windows 功能中确认 Windows Hypervisor Platform，再按提示重启电脑。

`build-staging` 使用 `%USERPROFILE%\.billrecord-staging-signing` 中由 DPAPI 保护的独立密钥。密钥只读挂载到构建容器，不进入仓库。需要代理时可传入 `-ProxyUrl`，或设置 `BILL_RECORD_PROXY` 环境变量。

staging 构建固定使用传入的 HTTPS 服务地址，且不会自动创建本地账本。登录并完成首次同步后才进入主界面；切换测试账号前需要在 Android 系统设置中清除该应用的数据。

模拟器适合日常 UI、记账逻辑、导入导出和同步调试。发布前仍需在真机验证生物识别、系统语音、后台通知、桌面组件、快捷磁贴、微信/支付宝无障碍解析和厂商权限策略。
