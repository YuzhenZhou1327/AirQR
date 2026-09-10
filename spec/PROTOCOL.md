# AirQR 线协议 v1.1

> 状态：定稿（2026-09-10）。本文件是两端的**唯一规范**：Go 为参考实现，Java(Android) 为对拍
> 实现，一致性由 `spec/vectors/cases.json`（golden vectors）锁定。改动协议必须：先改本文件 →
> 同步两端实现 → 重生成 vectors → 提升 BLOCK 头 version 字段。

## 1. 会话与数据流

- 发送端：`file bytes → LT 编码 K 个源块 → 无限喷泉块流`（zstd 预压缩为 P1，flags 预留 bit0）
- 一屏 `G` 个 BLOCK 帧 + 周期性 MANIFEST 帧；全屏黑底网格，循环播放。
- 接收端：任意时刻加入、任意顺序累积，按 `transfer_id` 归池，凑齐即解码。

## 2. BLOCK 帧（每个 QR 一个喷泉块，binary 模式）

| offset | size | 字段 | 说明 |
|---|---|---|---|
| 0 | 1 | magic | `0x51`（'Q'） |
| 1 | 1 | version | = 1 |
| 2 | 4 | transfer_id | uint32 LE，会话随机 ID |
| 6 | 4 | block_len | uint32 LE，喷泉块长（字节） |
| 10 | 4 | block_id | uint32 LE；`id < K` 为源块，`≥ K` 为 LT 冗余块 |
| 14 | B | block_data | LT 块本体（§4 格式） |

- 头共 14 字节，全部小端。QR payload 总长 `14 + block_len`。
- QR 版本/ECC 由 preset 决定。推荐 v40-L：binary 容量 2953B → `block_len ≤ 2939`，默认 2900。
- LT 约束：`K ≤ 8192`（K = ceil(size/block_len)；10MB/2900B ≈ 3641 ✓，文件上限 ≈ 23.7MB）。
- LT 头约束（§4）：`seed < 2^26` 且 `degree ≤ 64` → `block_len ≥ 5`。

## 3. MANIFEST 帧（文本模式 QR，JSON，会话头）

```json
{"fmt":"airqr1","tid":305419896,"name":"report.pdf","size":1048576,
 "blen":2900,"k":362,"zstd":0}
```

- `tid`：§2 transfer_id 的十进制形式；`name`：原始文件名（无路径）；`size`：原始字节数；
  `blen`：§2 block_len；`k`：源块数；`zstd`：0/1。不放时间戳等隐私元数据。
- 同一会话所有 MANIFEST 内容一致（幂等）；接收端拿到一次即可。
- 字符串安全约束：`name` 仅允许 ASCII 可打印字符（0x21–0x7E，除 `"` 和 `\`），
  违规名称发送端必须拒绝并提示用户改名。

## 4. LT 喷泉码（两端位级规范）

随机源：**唯一确定性 PRNG = splitmix64**，禁止实现另引随机源。
状态 `st`（64bit）；每步：

```
st += 0x9E3779B97F4A7C15
z  = st
z  = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9
z  = (z ^ (z >> 27)) * 0x94D049BB133111EB
z  = z ^ (z >> 31)
out32 = (uint32)z          // 取低 32 位
```

### 4.1 块生成（发送端；输入 K、blen、源块数组 src[0..K-1]、seed）

两条独立子流（同一 splitmix64 函数、两个初始状态）：

```
st0 = splitmix64_state(seed)                      // 子流A：产 seed_i
st1 = splitmix64_state(seed ^ 0xA5A5A5A5A5A5A5A5) // 子流B：产 degree 与 xor_mask
```

生成块 id = i 时：

```
d        = 4 + (out32(st1) % 61)        // degree，范围 4..64；st1 每生成一块前进一步
xor_mask = out32(st1)                   // v1.1 规定恒为 0：先读后弃用，不参与异或
seed_i   = out32(st0) % (1 << 26)       // 26bit 种子，写入块头；st0 每生成一块前进一步
```

- **全零退化处理**：若 XOR 结果全零（deg 小且命中重复源块时可能发生），
  丢弃本次 `seed_i`，继续从 st0 取下一个 `seed_i` 重试（st1 的 degree/mask 消耗不回退），
  直至块非全零。
- 块内容 `block_data`（写入 §2 的 14B 头之后）：

```
4B  len    uint32 LE   = 本块实际字节数（源块可为短块，LT 块 = 其 XOR 结果的长度）
L   payload len 字节   = XOR(各选中源块的 payload) ⊕ 0（v1.1 无 mask）
```

- **LT 块的位级布局（关键）**：`seed(4B LE) | len(4B LE) | payload(L)` —— 即块内
  前两个 u32 是 seed 与 len，其后是异或结果。选中源块集合由 `PRNG(seed_i, K)` 决定（§4.3）。

### 4.2 源块选择（两端一致实现）

```
st2 = splitmix64_state(seed_i * 0x9E3779B97F4A7C15 ^ K)
选中集合 = 空
重复 d 次：idx = out32(st2) % K；若集合非空且可继续选，允许重复索引
          （重复索引 XOR 两次 = 不选；标准 LT 允许，位级实现按此处理）
```

### 4.3 解码（接收端，Belief Propagation 剥离）

- 输入：若干 `(seed_i, len, payload)`；对每块按 §4.2 还原选中集合。
- 维护已知源块表 `S[i]`。规则：块选中集合中只有一个未知源块 → 该未知块 =
  payload XOR 全部已知选中项（长度取该源块自身 len，短块按零填充对齐）；
  每次剥离出新源块后，扫描所有块重复此过程，直至无进展。
- 选中集合为空或全已知的块对推进无贡献，直接忽略。
- 解码端不校验 seed 一致性（收到的块即权威）；同块重复到达幂等。
- 全部 K 个源块解出后按序拼接：源块 i 的 len：`i < K-1 → blen`；`i = K-1 → size-(K-1)*blen`。

## 5. 调度（发送端）

- `cycle = max(32, ceil(1.25 × K))`，块号空间 `[0, cycle)`（id ≥ K 的为冗余块）。
- PRNG：splitmix64，初始 `st = seed ⊕ 0x5EED5EED`；每 pass 前 reseed：
  `st = splitmix64_state(seed ⊕ 0x5EED5EED ⊕ (pass << 32))`。
- 每 pass 生成 `order = shuffle([0, cycle))`：Fisher–Yates，
  `for i = cycle-1 down to 1: j = out32(st) % (i+1); swap(order[i], order[j])`。
- 帧切分：每 `G` 个块号一帧；pass 帧数 = ceil(cycle / G)。
- MANIFEST 注入：每 pass 第 1 帧与正中帧各替换一个槽位为 MANIFEST（单码居中）；
  pass 帧数 > 200 时每 120 帧再补一个。被替换槽位的块号顺延到后续帧。
- 发送端循环播放（passes 可配或无限）；接收端完成即蜂鸣提示，发送端手动停止。

## 6. Golden vectors 契约

`spec/vectors/cases.json` 由 Go 测试生成并入库：

```json
{"cases":[{
  "case":"basic-1kb",
  "file_hex":"…","blen":2900,"k":362,"seed":772301445,
  "block_0_hex":"…",      // id=0 的完整 QR payload：14B 头 + 4B seed + 4B len + payload
  "manifest_json":"…"
}]}
```

- Java 端单测逐字节断言对 `block_0_hex` 的解析与 LT 解码结果（把 Go 当规范）。
- 另含固定锚点 case：`seed=9, K=1, blen=16, 全零数据, 深度 64` 的单块完整 payload。

## 7. 开放项

- zstd 预压缩（P1，flags.bit0 已预留）；加密 P2（XChaCha20+argon2id，flags.bit1）。
