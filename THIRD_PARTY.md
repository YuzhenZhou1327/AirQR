# THIRD_PARTY — 第三方组件与授权

| 组件 | 版本 | 用途 | 许可证 | 引入方式 |
|---|---|---|---|---|
| zxing core (Java) | 3.5.3 | Android 端 QR 解码 | Apache-2.0 | `android/libs/core-3.5.3.jar`（Maven Central，sha1=ca1349214a356cd7958651b2d5a0e1f3811a9c4b） |
| zxing-cpp (Python wheel) | 2.x | 开发机验证（bench/py） | Apache-2.0 | pip 依赖，不入库 |
| segno | latest | 开发机验证（bench/py 标定） | BSD-3-Clause | pip 依赖，不入库 |
| Go 标准库 | 1.27.1 | 发送端 | BSD-3-Clause | 工具链 |

第三方源码中**无 GPL/AGPL 成分**；仓库自身代码 MIT。
构建工具（Go 工具链、JDK、Android build-tools、SDL2）属构建环境依赖，不随产物分发条款约束（SDL2 若静态链接需在发布页附其 zlib 许可证声明——见 dist 发布流程）。
