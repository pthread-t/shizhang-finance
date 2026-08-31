# 第三方组件与许可

本仓库的原创代码和文档采用 [Apache License 2.0](LICENSE)。该许可不改变第三方代码、依赖库、模型或工具各自的授权条款。

## 随仓库提供的文件

- **Apache ECharts 5.6.0**：`app/src/main/assets/echarts.min.js`，采用 Apache-2.0。原许可和署名分别保存在同目录的 [`ECHARTS_LICENSE.txt`](app/src/main/assets/ECHARTS_LICENSE.txt) 与 [`ECHARTS_NOTICE.txt`](app/src/main/assets/ECHARTS_NOTICE.txt)。来源：[Apache ECharts 5.6.0](https://github.com/apache/echarts/tree/5.6.0)。
- **Gradle Wrapper**：`gradlew`、`gradlew.bat` 和 `gradle/wrapper/` 来自 Gradle。脚本保留上游 Apache-2.0 许可头；Gradle 及其内含组件的声明见其发行包。

## 构建时下载的组件

以下列出需要特别留意的直接依赖，并非完整的传递依赖清单。实际版本以各模块的 Gradle 配置为准。

| 组件 | 当前版本 | 授权说明 |
| --- | --- | --- |
| SQLCipher for Android | 4.17.0 | [BSD 三条款许可](https://github.com/sqlcipher/sqlcipher-android/blob/v4.17.0/LICENSE)，二进制分发时也须保留相应声明。 |
| argon2-jvm | 2.11 | [LGPL-3.0](https://github.com/phxql/argon2-jvm/blob/v2.11/LICENSE.txt)，用于服务端密码哈希，不属于本项目的 Apache-2.0 授权范围。 |
| Logback | 1.5.18 | 上游提供 [EPL-2.0 或 LGPL-2.1 双重许可](https://logback.qos.ch/license.html)。 |
| Google ML Kit 中文文字识别 | 16.0.1 | 遵循 [ML Kit 服务条款](https://developers.google.com/ml-kit/terms)，并非本项目以 Apache-2.0 授权的源码或模型。 |

分发 APK 或容器镜像时，需要根据实际打包的依赖保留其版权、许可与 NOTICE。LGPL 组件还涉及对应源码及重新链接等义务，不能用本仓库的 LICENSE 替代。自行部署与对外分发的要求并不相同。

ML Kit 在设备上处理识别输入，但可能向 Google 发送性能和使用统计。数据流向另见[隐私说明](docs/PRIVACY.md)。
