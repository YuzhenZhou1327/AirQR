# 构建（联网开发机执行，气隙内零构建）

```bash
# 工具链：Go 1.27+（GOPROXY=https://goproxy.cn,direct）、JDK25 + build-tools 35

# 发送端——三个目标的统一命令（CGO=0 → 纯静态）
export PATH=/d/tools/go/bin:$PATH
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o dist/airqr-linux-amd64 ./cmd/airqr
GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o dist/airqr-linux-arm64 ./cmd/airqr
go build -o airqr.exe ./cmd/airqr   # 开发机本地调试用

# 接收端 APK
cd android && sh build.sh

# 校验清单
cd dist && sha256sum airqr-linux-amd64 airqr-linux-arm64 airqr-v1.0.apk > SHA256SUMS
```

目标机选型：`uname -m` → `x86_64` 用 amd64，`aarch64` 用 arm64。

> 若将来需要 32 位 ARM（老树莓派/armv7）：`GOOS=linux GOARCH=arm GOARM=7 CGO_ENABLED=0`，
> 但注意 zxing 路径不受影响（仅发送端），10MB 内存受限设备建议 grid 降到 2。
