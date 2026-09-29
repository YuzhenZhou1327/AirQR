# AirQR 开发工作交接文档（HANDOFF）

> **交接时间**：2026-09-29 ｜ **交接快照**：v1.15（commit `40938da`，构建于 2026-09-11）
> **用途**：向接手 AirQR 开发的智能体/工程师移交全部工作状态、知识、环境与待办。
> **阅读顺序**：本文 → `README.md`（决策记录）→ `spec/PROTOCOL.md`（协议唯一规范）→ `docs/{BUILD,USAGE,E2E-CHECKLIST}.md` → 代码。
> **若接手方运行于本机 Hermes**：另加载技能 `software-development/airqr-airgap-qr-transfer`（项目横切铁律）与 `android-apk-build`（no-Gradle 构建链 + `references/camera-pitfalls.md` 相机真机坑）；本文 §6 已内联其关键内容，不依赖技能也可工作。

## 1. 项目一句话

气隙环境（无网络/蓝牙/USB）中，**Linux 电脑屏幕 → 安卓手机摄像头**，纯光学**单向**文件传输（≤10MB）。
发送端 = Go 单二进制（linux amd64/arm64，CGO=0 静态）；接收端 = 轻量 APK（zxing core 3.5.3 纯 Java，no-Gradle 手工链，minSdk 29）。
核心机制 = LT 喷泉码（自研，Go/Java 位级同构）+ 一屏多码（grid 2/4/6/8）+ 确定性乱序循环播放；支持手持、中途加入、中断续传。
硬约束 = 严格单向（无反馈信道）；"文件如何进入气隙环境"不在范围内。
验收锚点 = **1MB@4K 手持 40cm ≤90s 且 SHA-256 一致**（E2E 清单第 2 项）。

## 2. 位置与产物

| 项 | 位置 |
|---|---|
| 代码仓库 | `D:\proj\airqr`（git，main；远端 `git@github.com:YuzhenZhou1327/AirQR.git`，SSH 直推）|
| 交付目录（用户侧）| `D:\OneDrive\HermesWorkspace\airqr-delivery\`（二进制 + APK + SHA256SUMS + 文档）|
| 参考帧与演示 | `D:\airqr-demo\`（frames\*、play.html、test.pdf、bundle.zip、demo2.txt、testcard.png）|
| 桌面 harness | `android/desktop-test\`（SelfTestHarness / RotationSim / RotBench；README 含运行法）|
| 不入库（.gitignore）| `dist/`、`*.apk`、`android/keystore.properties`、`test-classes/` |

产物矩阵：`airqr-linux-amd64`、`airqr-linux-arm64`（发送端）、`airqr-v1.15.apk`（当前版接收端）。
发版纪律：**同步 `SHA256SUMS` + 从交付目录删除旧 APK**（防装错；用户曾多次以旧包测试导致误判）。

## 3. 当前状态（接手起点）

### 3.1 版本线

| 版本 | commit | 要点 | 真机状态 |
|---|---|---|---|
| **v1.15** | `40938da` | 持握方向改由 Activity 显示旋转（fullSensor）直驱；删除 OrientationEventListener 信念路径；RotationSim 6/6 / RotBench 锁定旋转数学 | **未装机 ← 交接点** |
| v1.14 | `fa0c66e` | Activity 改 fullSensor 随持握跟转 + configChanges 保活防重建 + stop() 清 transform | 未装机 |
| v1.13 | `b2eba06` | 持握方向第一版（传感器监听 + grid 转置 + 四档对焦 + 引导框贴合内容区）| 用户实测仍有问题 → 触发 v1.14/15 |
| v1.12 | `29a95f5` | 解码梯 R0–R4（半采样 cell / 全帧 global/hybrid / TRY_HARDER）+ 清晰度门控 + auto-torch + manifest `grid` 字段 | 未装机 |
| v1.11 | `6182355` | 修复 LT 选块流坍缩（顺序依赖**静默 corrupt**，历史最大坑）+ 负对照测试 | ✓ 用户真机通过 |
| v1.9 | `99f9a6a` | `getText()` 取数修复（rawBytes 含 mode 头导致全丢）+ 构建 fail-fast + 桌面 harness 建立 | ✓ 装机自检通过 |
| v1.0–v1.7 | `git log` | 启动闪退 / ISO-8859-1 / surface 竞态 / 自检 ANR 等，见 README 与 git log | — |

**注意**：v1.0–v1.14 的 APK 均有已知缺陷，勿装机；交付目录只保留 v1.15。

### 3.2 已验证（可复跑的证据）

- **真机 e2e 三例**（README 表，v1.9–v1.11 时代）：`demo2.txt` 2136B/K=2、`test.pdf` 77787B/K=65、`bundle.zip` 41924B/K=35——SHA-256 全部一致，文件可打开、内容正确。
- **跨端位级一致**：Golden vectors 5/5 PASS（含 `sel_vectors` 选块映射与 LT-块先喂用例；负对照：旧解码器恰好挂那两道）。
- **第三方往返**：`bench/py/roundtrip.py`（zxing-cpp 解码 + 独立 Python LT 重组）PASS。
- **旋转数学（桌面仿真）**：`RotationSim` 6/6 —— T1 现场锚点精确相等、T2 对焦往返（全持握×双 mount×双视图×5 落点）、T3 letterbox 永不变形不出界、T4 grid 切分保码完整 + 负对照（v1.12 无转置必被切）、T5 AOSP 公式表。
- **转置规则（真渲染帧）**：`RotBench` —— 横持 as-is 2/2；竖持转置 2/2、不转置 0/2。

### 3.3 未验证（接手第一优先级）

- **v1.13–v1.15 持握方向链路的真机行为**：HAL 显示合成 / SurfaceTexture 存活 / MIUI 转屏特性——桌面仿真覆盖不到，必须真机。
- 用户侧 v1.15 装机验证（E2E 清单第 9/10 项横/竖持对比，从未执行）——**截至交接未收到实测回执**；接手后先向用户确认（若已测过，回填结果）。

### 3.4 用户当前诉求（背景）

用户要求"**横持手机时二维码占满画幅**"（横持 16:9 满幅 vs 竖持约 31% 屏宽，约 3 倍像素红利）。
用户横持实测反馈（截图）：「画面只有一小格，有扭曲，且绿色引导框还是竖屏的模式」；随后要求「旋转逻辑还是有问题——你先自己模拟一下再交付」。
v1.14/v1.15 为对应修复；**v1.15 待装机验证（截至交接未收到回执），是本次交接的悬起点**。
若回执仍异常：抓屏上诊断行（含 P/L 持握标记）+ `adb logcat -s AirQR`（关注 `display orientation ->` 行），对照 §7 缩小到 framework/HAL 层。

## 4. 环境与工具链（本机精确路径）

| 组件 | 值 |
|---|---|
| Go | `D:/tools/go/bin`（go 1.27.1；**GOPROXY=https://goproxy.cn,direct 必须**）|
| JDK | `C:/Users/23653/jdk/jdk-25.0.4.1+1`（**d8 铁律：JDK25 必须配 build-tools 35**）|
| Android SDK | `C:/Users/23653/Android/sdk`（build-tools-35 + android-34）|
| adb | `D:/tools/platform-tools/platform-tools/adb.exe`（v37.0.1）|
| 签名 | keystore：`C:/Users/23653/Android/keystore/airqr.keystore`；口令文件 `android/keystore.properties`（一行 `KS_PASS=...`；**不入库、不写入任何文档**；交接单独渠道）|
| 测试机 | 小米/HyperOS（USB 调试；adb id 曾为 `b44fcdd5`，以 `adb devices` 为准）|
| 网络 | clash 代理 `127.0.0.1:7897`；GitHub 走 SSH key，无需代理 |
| 路径注意 | native 工具（javac、java、dexdump、adb）**不认 MSYS 路径** → 一律传 `C:/` 风格；scratch 用 `$LOCALAPPDATA/Temp` 或 Hermes scratch（24h 自动清理，持久 harness 放仓库）|

## 5. 常用命令速查（复制即用）

### 5.1 发送端（Go）

```bash
cd D:/proj/airqr
export PATH=/d/tools/go/bin:$PATH GOPROXY=https://goproxy.cn,direct
go test ./...                 # 全绿再动手；双架构 vet：GOOS=linux GOARCH=amd64 go vet ./...
AIRQR_GEN_VECTORS=1 go test ./internal/lt/ -run TestGenerateGoldenVectors   # 改协议/选块后必跑
python bench/py/gen_vectors_fixture.py        # cases.json → android/test/vectors.tsv
python bench/py/roundtrip.py <frames_dir> <orig_file>     # 第三方往返验证
# 交叉编译（CGO=0 纯静态）
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o dist/airqr-linux-amd64 ./cmd/airqr
GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o dist/airqr-linux-arm64 ./cmd/airqr
```

### 5.2 接收端（APK）

```bash
cd D:/proj/airqr/android && sh build.sh    # fail-fast（javac 禁 ||true；d8 后 dexdump 断言）
# 发版前先改三处版本：AndroidManifest.xml 的 versionCode(+1)/versionName 与 build.sh 的 VERSION
```

### 5.3 一致性 / 仿真（桌面，无手机）

```bash
# JUnit golden 对拍（Windows classpath 用 ;）
cd D:/proj/airqr
javac -encoding UTF-8 -cp "android/libs/core-3.5.3.jar;test-libs/junit-platform-console-standalone-1.10.2.jar" \
  -d test-classes android/test/GoldenVectorsTest.java android/src/com/airqr/core/*.java
java -jar test-libs/junit-platform-console-standalone-1.10.2.jar execute \
  --class-path "test-classes;android/libs/core-3.5.3.jar" --scan-class-path --include-classname "GoldenVectorsTest"

# 旋转仿真（期望 SIM ALL PASS）+ 转置基准（需渲染帧 PNG），详见 android/desktop-test/README.md
cd android/desktop-test
javac -encoding UTF-8 -cp "../libs/core-3.5.3.jar" -d "$T/rotsim" RotationSim.java ../src/com/airqr/camera/GridCells.java
java -cp "$T/rotsim;../libs/core-3.5.3.jar" RotationSim
```

### 5.4 真机

```bash
ADB="D:/tools/platform-tools/platform-tools/adb.exe"
"$ADB" devices
"$ADB" install -r D:/proj/airqr/android/build/airqr-v1.15.apk
"$ADB" logcat -s AirQR
```

### 5.5 一次完整发版

1. 版本三处同步：`AndroidManifest.xml` versionCode(+1) / versionName、`build.sh` VERSION
2. 构建：`sh build.sh`；发送端有改动则同时交叉编译双架构
3. 校验：`cd dist && sha256sum --text airqr-linux-amd64 airqr-linux-arm64 airqr-vX.Y.apk > SHA256SUMS`
4. 交付：APK + SHA256SUMS 拷到 `airqr-delivery/`，**删除旧 APK**，`sha256sum -c SHA256SUMS` 复核
5. 入库：`git add -A && git commit && git push origin main`；`git ls-remote origin refs/heads/main` 核对与本地 HEAD 一致

## 6. 血泪铁律（全部有事故背书，动手前必读）

1. **协议改动顺序**：`spec/PROTOCOL.md` → Go → 重生成 golden（AIRQR_GEN_VECTORS=1）→ 重生成 Java 夹具（gen_vectors_fixture.py）→ Java 对拍 → roundtrip → 双端出包。任何一环跳过 = 两端漂移。
2. **zxing-java 取数铁律**：`getRawBytes()` 含 mode/length 头（43B 负载 → 274B）→ 必须 `getText()` + ISO-8859-1 在先。（v1.7"自检无反应"根因：所有二进制帧静默全丢。）
3. **Java 位运算**：`1L<<64==1L`；long 乘法自然 mod 2^64，`remainderUnsigned(x, 1L<<64)≡0`——曾把 LT 选块流坍缩成常量集合，与 Go 分叉造成**顺序依赖静默 corrupt**（v1.10 症状：没扫完就"完成"+ 后半乱码）。写 `seed * DEGREE_MIX ^ k`，禁止多余 masking。
4. **Golden 必须覆盖 LT 路径**：顺序喂块时源块收齐会提前 break，`addLt` 一行跑不到（假绿）。两道网：`selectionsMatchGo` + `ltBlocksFirstDecodeByteExact`，均经负对照验证。
5. **构建链 fail-fast**：javac 行禁 `|| true`（v1.8 残缺 dex → 启动闪退事故）；d8 后必须 dexdump 断言 MainActivity + zxing 在 dex 内。
6. **真机排障协议**：先升级到最新 APK（**版本号自报**）→ 屏上诊断行（帧数/阶段/P-L）→ 最后 logcat；**一次只修一个根因**；出包必升版本号。
7. **方向驱动用 ground truth**：以 Activity 显示旋转（fullSensor）直算 display orientation；禁传感器"信念"路径（v1.13 教训：无独立仲裁者的传感器读数会与 UI 卡死分歧且无法自证）。
8. **仿真边界**：能仿的（协议/喷泉码/解码链/旋转数学）必须桌面仿完再交付；不能仿的（HAL/SurfaceTexture/OEM 转屏）必须真机——不得用推测代替。
9. **验证器也要怀疑**：bench/py 是独立实现；验证器自己的 bug 会伪装成"解码失败"——先怀疑验证器与被验证者各一半。
10. **第三方解码器 round-trip 才算数**：自己编自己解不算验证（roundtrip.py 用 zxing-cpp）。
11. **发版卫生**：同步 SHA256SUMS + 删旧 APK；APK 版本号必须能自报（排障前置）。
12. **相机真机坑**：surface 生命周期竞态、TextureView letterbox、CHARACTER_SET=ISO-8859-1、屏上诊断——详见 `android-apk-build` 技能 `references/camera-pitfalls.md`。

## 7. 桌面仿真边界（能仿 vs 不能仿）

- **能仿（已仿）**：协议编解码、喷泉码选块/度分布、解码梯、旋转数学（letterbox/对焦映射/grid 转置/AOSP 公式）、NV21 链。工具 = `android/desktop-test/` + `bench/py/`。
- **不能仿**：HAL 显示合成、SurfaceTexture 存活、MIUI 转屏行为、相机硬件本身。
- 历史定案案例（离线复现→修复→再验证）：v1.7 rawBytes 由 SelfTestHarness 定案；v1.11 LT bug 由桌面 DecodeDemo 复现（GARBLED → BYTE-EXACT）；v1.15 旋转数学由 RotationSim/RotBench 锁定。
- 备用路线（未采用）：Android emulator + 本机 webcam 输入可做框架级仿真（约 2–3GB 下载 + Hyper-V），需要时再评估。

## 8. 代码地图

| 路径 | 内容 |
|---|---|
| `cmd/airqr/main.go` | 发送端 CLI：send / render / testqr |
| `internal/lt` | LT 喷泉码（degree v2、splitmix64 唯一随机源、cycle=1.75×K）+ golden 生成 |
| `internal/wire` | 18B 头 + CRC32 打包 / manifest 解析 |
| `internal/schedule` | 确定性乱序循环调度 |
| `internal/render` | PNG 网格渲染（统一 v40）|
| `android/src/com/airqr/core` | Java 同构：Wire / LtDecoder / FountainSession / SplitMix64（纯 Java，可桌面测）|
| `android/src/com/airqr/camera` | CameraController / QrGridAnalyzer（解码梯）/ GridCells / HalfSampleLuminanceSource / Nv21 / MockCamera |
| `android/src/com/airqr/ui` | MainActivity（方向直驱）/ FrameGuideView（引导框）|
| `android/desktop-test` | 桌面 JVM harness：SelfTestHarness / RotationSim / RotBench |
| `android/test` | GoldenVectorsTest + vectors.tsv（对拍夹具）|
| `spec/` | PROTOCOL.md（唯一规范）、qr-capacity.md、vectors/cases.json |
| `bench/py` | 第三方验证与仿真：roundtrip / multipass_sim / losssim / camera_sim / qr_capacity / gen_vectors_fixture |
| `docs/` | BUILD / USAGE / E2E-CHECKLIST / HANDOFF（本文）|

## 9. 接手第一件事（按序执行）

1. **离线全绿**（不碰手机）：`go test ./...`、JUnit golden、RotationSim、RotBench、roundtrip.py。
2. **装机 v1.15**（`airqr-delivery/airqr-v1.15.apk`），跑 E2E 第 9/10 项（横/竖持对比）+ 回归一例小文件 e2e（可参考 `demo2.txt` 2136B）。
3. **处理回执**：若横持仍有异常，按 §3.4 收集证据后定位；旋转数学层已被 RotationSim 锁定，优先怀疑 framework/HAL 层。
4. **继续 backlog**：P2 项——zstd 预压缩 / 加密 / 自举安装 / 双机模式。
5. **保持纪律**：一次一因、出包升版本、SHA256SUMS 同步、真机验证后才算完成。

### 协作习惯（写给接手方）

- 先分析、后动手；结论要数据/证据背书——**用户对数据错误零容忍**，禁估测/反推。
- 交付前先自模拟/自验证（用户原话：「你就没有办法自己模拟一下再交付吗」）。
- 报问题要附**可执行的下一步**（命令/检查项），不要只给结论。
- 每个修复配套**可复跑**的验证；发现假阳性（如假 PASS）要主动指出。

## 10. 交接检查单

- [x] 仓库已推送远端（`03d0a4e`，含本文档与 RotationSim / RotBench）
- [x] 交付目录与 dist 同步 v1.15（SHA256SUMS 校验通过）
- [x] 桌面仿真全绿（RotationSim 6/6、RotBench VERIFIED、GoldenVectors 5/5、roundtrip PASS）
- [ ] keystore 口令交接（单独渠道，勿写入文档/仓库）
- [ ] v1.15 装机回执（横/竖持）——**接手方第一位任务**

---

*本文档与仓库同源：`docs/HANDOFF.md`；交付目录副本：`airqr-delivery/HANDOFF.md`。*
