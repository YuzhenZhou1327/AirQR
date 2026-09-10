# AirQR — 气隙环境二维码单向文件传输

Windows/Linux 电脑屏幕 → 安卓手机摄像头，纯光学单向传输，无需任何网络/蓝牙/USB。
发送端为 Go 单二进制；接收端为轻量安卓 APK（zxing core 解码，无 Google Play 依赖）。
文件 ≤10MB，LT 喷泉码 + 一屏多码 + 确定性乱序循环播放，支持手持、中途加入与中断续传。

## 快速使用

```bash
# 发送端（联网开发机交叉编译为静态产物，拷入气隙机）
airqr send report.pdf                # 默认 4K 6码@3fps；空格暂停，↑↓调fps，q退出
airqr render report.pdf --out frames # 离线渲染 PNG 序列（feh 兜底播放模式）
```

```text
手机端：安装 airqr.apk → 打开取景 → 把电脑屏幕装进取景框 → 等待进度条走完
        → 蜂鸣提示 → SAF 选择保存位置
```

## 架构

```
file → LT fountain encode → wire 打包 → 调度(pass乱序+MANIFEST注入)
     → PNG 网格渲染 → SDL2 全屏循环 / feh 兜底
                          ↓ 屏幕光线（单向）
     camera2 → zxing QR 解码 → wire 解析 → LT 解码池 → SHA-256 → 保存
```

- **协议唯一规范**：`spec/PROTOCOL.md` v1.1；两端一致性由 golden vectors 锁定。
- **LT 喷泉码**：纯实现（robust soliton 同族，degree 4..64，splitmix64 确定性 PRNG），
  Go 与 Java 两端同构实现、位级一致；零 cgo/NDK 依赖。
- **单向设计**：无反馈信道。接收端凑齐 K+ε 个任意唯一块即解出（ε≈5%）；
  发送端循环播放直到用户停止（看到手机蜂鸣即可停）。

## 决策记录（2026-09-10）

1. 文件规模 <10MB（用户确认）；接收机 Android 10+；严格单向（无 Linux 摄像头）。
2. 喷泉码选型：原计划 wirehair(C)；因本机无 C 工具链/NDK，改纯 LT 码，两端对拍。
   吞吐上限参照：libcimbar（wirehair）~106KB/s > 本项目 ~45–70KB/s 目标 > txqr ~9KB/s。
3. Android 端不用 ML Kit（bundled 依赖重 + binary rawBytes 有截断风险记录），
   用 zxing core 3.5.3（纯 Java jar，Apache-2.0），binary 模式经 e2e 验证。
4. 构建链：no-Gradle（javac+d8 手工 APK，铁律 build-tools 35 × JDK25）。
5. 开发机标定：QR 容量实测 v40-L=2953B/v25-L=1273B/v40-M=2331B（`spec/qr-capacity.md`）。

## 里程碑

M0 骨架+标定 ✓ → M1 协议+喷泉码+渲染+golden round-trip → M2 CLI+播放器+参数标定
→ M3 Android → M4 e2e → M5 健壮性 → M6 交付（气隙分发：产物只拷贝+核 sha256）。

## 开发

```bash
go test ./...          # 单测（线格式/喷泉/调度）
pytest bench/py        # 第三方验证（round-trip/bench matrix）
cd android && sh build.sh   # APK（需 JDK25 + build-tools 35）
```
