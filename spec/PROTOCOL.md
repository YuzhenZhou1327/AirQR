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

- 头共 14 字节，全部小端。QR payload 总长 `14 + block_data`。
- `block_data` 校验规则：`1 ≤ len(block_data) ≤ 4 + block_len`
  （源块 = 4B seed + min(blen, 剩余)；LT 块 = 4B seed + blen）。
- QR 版本/ECC 由 preset 决定。推荐 v40-L：binary 容量 2953B → `block_len ≤ 2939`，默认 2900。
- LT 约束：`K ≤ 8192`（K = ceil(size/block_len)；10MB/2900B ≈ 3641 ✓，文件上限 ≈ 23.7MB）。
- LT 约束：槽位种子 `seed < 2^26`；degree ∈ 4..64 由 seed 派生（§4.1），不上线路。

## 3. MANIFEST 帧（文本模式 QR，JSON，会话头）

```json
{"fmt":"airqr1","tid":305419896,"name":"report.pdf","size":1048576,
 "blen":2900,"k":362,"zstd":0}
```

- `tid`：§2 transfer_id 的十进制形式；`name`：原始文件名（无路径）；`size`：原始字节数；
  `blen`：§2 block_len；`k`：源块数；`zstd`：0/1。不放时间戳等隐私元数据。
- 同一会话所有 MANIFEST 内容一致（幂等）；接收端拿到一次即可。
- 字符串安全约束：`name` 仅允许 ASCII 可打印字符（0x20–0x7E，除 `"`、`\`、`/`），
  长度 1–128；违规名称发送端必须拒绝并提示用户改名（禁 `/` 防路径穿越）。

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

### 4.1 块生成（发送端；输入 K、blen、源块数组 src[0..K-1]、sessionSeed）

随机源只有一条 splitmix64 子流，初始 `st = sessionSeed`。
**槽位种子预计算**：编码器初始化时一次性从 st 顺序抽 `cycle - K` 个
`seed_j = out32(st) % 2^26`（遇全零退化按本节末尾规则重抽，重抽消耗继续），
固定映射到冗余槽位 `id ∈ [K, cycle)`。同一槽位在任何 pass 中内容不变
（调度只重排槽位顺序，见 §5）；因此接收端中途加入任意 pass 都能正确归池。

**每个槽位的块内容完全由其 seed 自包含推导**（degree 不上线路，接收端可从
seed 还原）：

```
st2 = splitmix64_state(seed_i * 0x9E3779B97F4A7C15 ⊕ K)
u   = out32(st2) % 10000                   // 0..9999
按 u 选度数分支（度数分布 v2，含低度 ripple，端局可剥离）：
  u <  200  → deg = 1                      // 2%
  u < 1500  → deg = 2                      // 13%
  u < 2900  → deg = 3                      // 14%
  u < 4000  → deg = 4                      // 11%
  u < 6500  → deg = 5  + out32(st2) % 6    // 25%: 5..10
  else      → deg = 11 + out32(st2) % 54   // 35%: 11..64
随后同流连抽 deg 次：idx = out32(st2) % K，选中集合按「重复索引即抵消」累积
（sel[idx] = !sel[idx]）
```

- 度数分布设计依据：均匀 4..64 无低度方程，解码端局博弈（remaining 很小时）
  剥离概率趋零；2+13+14+11=40% 的块度数 ≤4 提供 ripple 种子，
  高度分支保证覆盖。经验证 K ∈ [8,8192] × 丢码 0–30% × ≤2 pass 可解（M1 测试）。

- **退化处理**：仅当**选中集合为空**（deg 次抽取全部成对抵消）时块无意义，
  丢弃该 `seed_j`，从 st 继续抽下一个 seed 重试（st 单调前进，不回退）。
  XOR 结果全零**不是**退化（合法方程 `0 = Σ选中`，解码器照常使用，
  全零文件因此可正常传输）。
- 块内容 `block_data`（§2 的 14B 头之后）：

```
4B  seed    uint32 LE   槽位种子；id < K 的源块槽位恒为 0
L   payload             见下
```

- payload 长度由 block_id 决定（接收端从 manifest 推导，无需额外字段）：
  - `id < K`（源块）：`L = min(blen, size − id×blen)`，内容为原始分块；
  - `id ≥ K`（LT 块）：`L = blen`，内容为 `XOR(各选中源块零填充到 blen)`，
    **恒定 blen 字节，不做尾部截断**。
- 源块与 LT 块统一为 `4B seed + payload` 布局，解析器无分支差异。

### 4.2 解码（接收端，Belief Propagation 剥离）

- 输入：若干 `(id, seed, payload)`；`id < K` 直接记为已知源块；
  `id ≥ K` 按 §4.1 从 seed 还原 `deg` 与选中集合。
- 维护已知源块表 `S[i]`（内部零填充到 blen）。规则：某方程的未知源块
  恰剩一个 → 该未知块 = 方程数据 XOR 全部已知选中项；每次剥离出新源块后，
  只回访含该源块的方程（倒排索引），直至无进展或解出全部 K。
- 选中集合为空或全已知的方程无贡献，丢弃。
- 解码端不校验 seed 一致性（收到的块即权威）；同块重复到达幂等。
- 全部 K 个源块解出后按序拼接，源块 i 的长度 `min(blen, size − i×blen)`，
  截齐到 size。

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
  "block_0_hex":"…",      // id=0 的完整 QR payload：14B 头 + 4B seed(=0，源块) + payload
  "block_c0_hex":"…",     // id=K 的第一个冗余槽位完整 payload（含其 4B seed）
  "manifest_json":"…"
}]}
```

- Java 端单测逐字节断言：`block_0_hex`/`block_c0_hex` 的头与 payload、以及用全部
  vectors 块喂 Java Decoder 后重组出的文件与 `file_hex` 一致（把 Go 当规范）。
- 另含固定锚点 case：`seed=9, K=2, blen=16`，文件 = `000102…0f 101112…1f`
  （32 字节）；`block_c0_hex` = 冗余槽位 id=2 的完整 payload（第一个抽出的
  槽位种子及其异或结果，逐字节固定，供两端快速对拍）。

## 7. 开放项

- zstd 预压缩（P1，flags.bit0 已预留）；加密 P2（XChaCha20+argon2id，flags.bit1）。
