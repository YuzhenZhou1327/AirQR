# AirQR — 气隙环境二维码单向文件传输

Linux 电脑屏幕 → 安卓手机摄像头，纯光学单向传输，无需任何网络/蓝牙/USB。
发送端为 Go 单二进制（无 cgo，amd64/arm64 静态编译）；接收端为轻量安卓 APK
（zxing core 纯 Java 解码，无 Google Play 依赖）。文件 ≤10MB，LT 喷泉码 +
一屏多码 + 确定性循环播放，支持手持、中途加入与中断续传。

状态：真机端到端已验证（APK v1.11，见下表）。协议唯一规范见 `spec/PROTOCOL.md`。

## 实证传输记录（真机，小米/HyperOS）

| 文件 | 大小 | K | 结果 |
|---|---|---|---|
| demo2.txt（文本） | 2136 B | 2 | 成功，SHA-256 逐字节一致 |
| test.pdf（25 页） | 77787 B | 65 | 成功，手机端可打开、内容正确 |
| bundle.zip（PDF+PNG 测试卡） | 41924 B | 35 | 成功，解压后两文件正确 |

## 快速使用

```bash
# 发送端：在气隙机上（或开发机渲染后拷入）
airqr send report.pdf                # 默认 4K 4码@3fps 全屏循环；空格暂停，↑↓调fps，q退出（Linux 用 feh 播放）
airqr render report.pdf --out frames # 离线渲染 PNG 序列（无播放器环境用，任意看图软件全屏轮播）
```

```text
手机端：安装 airqr-v1.11.apk → 点「开始传输」→ 把电脑屏幕装进取景框
        → 进度走到 K/K → 蜂鸣震动 → 保存
```

小文件（K=2）几秒收完、大文件（K=65）需扫大半轮——都是喷泉码的正常行为：
解码只需 K+少量冗余个独立方程，不是收满全部槽位；收不齐时绝不宣布完成（见 `docs/E2E-CHECKLIST.md`）。

## 架构

```text
file → LT 喷泉码 → wire 打包(18B头+CRC32) → 调度(乱序循环+MANIFEST注入)
     → PNG 网格渲染 → feh 全屏循环
                          ↓ 屏幕光线（单向，无反馈）
     Camera旧API + TextureView → zxing 多码解码 → wire 解析 → LT 解码池 → SHA-256 → 保存
```

- **LT 喷泉码**：自研纯实现（degree 分布 v2，splitmix64 唯一随机源），Go 与 Java
  同构、逐比特一致；cycle=1.75×K。跨端一致性由 `spec/vectors/cases.json` +
  `sel_vectors` 映射向量锁定（`android/test/GoldenVectorsTest.java` 5 道）。
- **单向设计**：无反馈信道。发送端循环播放直到用户停止（看到手机蜂鸣即可停）。

## 决策记录

1. 文件规模 <10MB；接收机 Android 10+ 可侧载；严格单向（Linux 侧无摄像头）。
2. 喷泉码选型：原计划 wirehair(C)；因无 C 工具链/NDK，改纯 LT 码，两端对拍。
   吞吐参照：libcimbar（wirehair）~106KB/s > 本项目目标 ~45–70KB/s > txqr ~9KB/s。
3. Android 不用 ML Kit（依赖重），用 zxing core 3.5.3（纯 Java jar，Apache-2.0）。
   教训：`getRawBytes()` 含 mode/length 头不能直接用，必须 `getText()`+ISO-8859-1
   在先；`1L<<64==1L`，seed 混合禁止多余 masking（v1.10 静默 corrupt 根因，见 git log）。
4. 构建链：no-Gradle（javac+d8 手工 APK，build-tools 35 × JDK25），d8 必须同时喂
   classes+外部 jar；javac 行禁 `|| true`（fail-fast，dex 后用 dexdump 断言）。
5. QR 容量标定：v40-L=2953B / v25-L=1273B / v40-M=2331B（`spec/qr-capacity.md`）。

## 里程碑

M0 骨架+标定 ✓ → M1 协议+喷泉码+渲染+round-trip ✓ → M2 CLI+播放 ✓
→ M3 Android ✓ → M4 e2e ✓ → M5 健壮性（CRC32+乱序回归） ✓ → M6 交付 ✓

## 开发

```bash
# 发送端（Go 1.27+；国内 GOPROXY=https://goproxy.cn,direct）
go test ./...                                   # 线格式/喷泉/调度/渲染
AIRQR_GEN_VECTORS=1 go test ./internal/lt/ -run TestGenerateGoldenVectors
python bench/py/gen_vectors_fixture.py          # cases.json → android/test/vectors.tsv
python bench/py/roundtrip.py <frames> <orig>    # 第三方解码往返（zxing-cpp）
GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o airqr-linux-arm64 ./cmd/airqr

# 接收端（JDK25 + build-tools 35；签名口令放 android/keystore.properties，一行 KS_PASS=...，该文件不入库）
cd android && sh build.sh
# JVM 交叉测试（JUnit standalone，仓库自带 test-libs/；Windows 用 ; 分隔 classpath）
javac -encoding UTF-8 -cp "libs/core-3.5.3.jar;test-libs/junit-platform-console-standalone-1.10.2.jar" \
  -d test-classes android/test/GoldenVectorsTest.java android/src/com/airqr/core/*.java
java -jar test-libs/junit-platform-console-standalone-1.10.2.jar execute \
  --class-path "test-classes;libs/core-3.5.3.jar" --scan-class-path --include-classname "GoldenVectorsTest"
```

目录：`cmd/airqr` 发送端 · `internal/{lt,wire,schedule,render}` 核心 ·
`android/src` 接收端 · `spec/` 协议+向量 · `bench/py` 第三方验证 ·
`docs/{USAGE,BUILD,E2E-CHECKLIST}.md` 文档。

说明：`dist/`（双架构二进制+APK+SHA256SUMS）与 `*.apk` 已在 `.gitignore` 中，
不入库；对外发布时作为 GitHub Release 附件并附 SHA256SUMS 校验。
