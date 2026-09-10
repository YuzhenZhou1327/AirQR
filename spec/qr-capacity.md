# QR binary 容量标定（2026-09-10 实测）

工具：segno（受控编码）+ zxing-cpp 2.x（独立第三方解码），`bench/py/qr_capacity.py` 可复跑。
方法：对每个 (version, ECC) 二分实测最大 byte-mode 容量，并在容量值/容量-1/2914B 处
做 PNG→解码往返，逐字节比对。

| QR 版本 | ECC | 容量 (bytes, 实测) | ISO 参考值 | 往返@cap | 往返@cap-1 | 2914B 特判 |
|---|---|---|---|---|---|---|
| 10 | L | 271 | 271 | ✓ | ✓ | n/a |
| 25 | L | 1273 | 1273 | ✓ | ✓ | n/a |
| **40** | **L** | **2953** | 2953 | ✓ | ✓ | **OK** |
| 10 | M | 213 | 213 | ✓ | ✓ | n/a |
| 25 | M | 997 | 997 | ✓ | ✓ | n/a |
| 40 | M | 2331 | 2331 | ✓ | ✓ | n/a |

结论：
- 实测与 ISO 表完全一致；**PROTOCOL §2 的 block_len ≤ 2939（v40-L）成立**，默认 2900。
- v40-M 上限 2331B → 低光 preset 的 block_len 上限 2317，默认 2200。
- segno 对 2914B binary 自动升-version 后落 v40（+2 模块）不影响容量上限；我们的渲染器
  将固定 version=40，segno 参数 version 固定即可。
- 全部往返逐字节相等 → binary 模式经 zxing-cpp 解码无损（MLKit 的 rawBytes 风险仅针对
  Android 端，e2e 阶段实测兜底）。
