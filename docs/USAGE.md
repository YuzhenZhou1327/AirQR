# AirQR 气隙安装与使用指南

> 气隙铁律：一切构建都在联网开发机完成；气隙内只发生「拷入产物 + 核对 sha256」，不安装任何东西。

## 产物

| 产物 | 用途 | 校验 |
|---|---|---|
| `airqr-linux-amd64` | Linux 发送端单文件 | `sha256sum` |
| `airqr-v1.0.apk` | 安卓接收端 | `sha256sum` |

安装包本身过气隙的方式由你选择（纸 QR / 物理介质 / 现有跨网闸流程）；AirQR 只负责文件传输。

## 发送端（Linux 电脑）

```bash
# 1) 核对哈希（与交付清单一致后继续）
sha256sum airqr-linux-amd64

# 2) 赋执行权限并运行
chmod +x airqr-linux-amd64
./airqr-linux-amd64 send report.pdf            # 默认 4 码@4K
./airqr-linux-amd64 send report.pdf --grid 6   # 更多码 = 更快，但要求屏更大/更近
./airqr-linux-amd64 send report.pdf --grid 2 --version 25   # 低配屏（1080p）模式

# 离线渲染成 PNG 序列（无显示服务器时）
./airqr-linux-amd64 render report.pdf --out frames --grid 4
# 用 feh 全屏循环播放 PNG
feh --fullscreen --slideshow-delay 0.33 --recursive frames
```

操作：`空格` 暂停/继续，`↑/↓` 调整 FPS，`q` 退出。屏幕上会显示 pass 进度。

参数怎么选：
- 屏幕分辨率 ≥2560×1440：`--grid 6`（默认 4 更稳）
- 1080p：`--grid 2 --version 25`
- 文件 ≤1MB 时任意档都很快（<1 分钟）

## 接收端（安卓手机 ≥ Android 10）

1. 核对 APK sha256 → 侧载安装（需要允许「安装未知应用」）。
2. 打开 AirQR，授权相机。
3. 手机对准电脑屏幕，把整屏二维码装进取景框（黑色卡片=二维码）。
4. 保持稳定（30–60cm），等进度条走满 + 蜂鸣震动。
5. 点「保存文件」→ 存入 Downloads/AirQR/；显示的 SHA-256 前 8 位可与发送端核对。

中断/没扫全都没关系：喷泉码支持随时加入/离开/中途离手，重新对准屏幕即续传
（同一传输会话自动累积）。

## 常见问题

- **进度不动**：离屏幕太远/反光。凑近、调角度避开灯光反射。
- **速度慢**：帧率没对齐。发送端调 FPS（默认 3fps，最稳）；手机保持相对静止。
- **多屏/投屏**：AirQR 扫描整帧，投屏会缩小二维码，建议直连显示器。
- **换文件重发**：发送端 Ctrl+C 退出后重启即可；手机端点「继续接收下一个」。

## 环境建议

- 关灯或避免屏幕正上方强光（防反光）。
- PWM 调光屏（低亮度频闪）建议调高亮度。
- 手持 30–60cm；屏幕越大越远也能扫（zoom 自动辅助）。
