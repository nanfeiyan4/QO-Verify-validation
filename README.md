# QO-Verify 机制验证

> 仓库：<https://github.com/nanfeiyan4/QO-Verify-validation>

对 CIDR 2026 论文 **《Leveraging Query Optimizers to Verify the Soundness of LLM-based Query Rewrites for Real-World Workloads, and More!》**（Narasayya & Chaudhuri, Microsoft）的**独立复现**。

> 本仓库复现的是**机制**（方法是否可行、结论是否成立），**不追求数值一致**。论文的 4 个企业库为微软私有数据、SQL Server 的 memo 导出为微软内部改动，因此覆盖率 37%、开销 1.2 s 这类数字不可移植；可复现的是「规则集决定覆盖率」这一机制。

---

## 论文主张与本仓库的检验方式

| 论文主张 | 本仓库的检验 |
|---|---|
| QO-Verify 用优化器 memo 的「组求交」判定语义等价（Algorithm 1） | 玩具 Cascades 精确复现 Figure 2 的 memo（6 group / 11 表达式 / 隐含 8 棵完整逻辑表达式），并实现那 17 行匹配算法 |
| 检查是 **sound** 的（绝不误报 True） | 真实 SQL + 随机数据库 fuzz：QO-Verify 判 True 的每一对，fuzz 都找不到反例 |
| 覆盖率**是规则集的函数**，不是算法的函数 | 两处消融：玩具层增/删规则；Calcite 层去掉 join 交换/结合规则 |
| 缺失的规则可从 LLM 改写里「蒸馏」出来（§3 三条规则） | §3.1 / §3.2 / §3.3 三条规则全部做成「规则不在 → Unknown，规则加入 → True」 |
| 规则是**条件保序**的 | 对 §3.2 / §3.3 的前提条件逐条做破坏性 fuzz |

---

## 复现内容

### 1. 玩具 Cascades（`toy/`）

纯 Python（无依赖），用于把论文的**算法本身**跑通并可视化。

- **`fig2_qoverify.py`**
  - 精确构造 Figure 2 的 memo：`Group 4 = {4.1, 4.2}`（交换律）、`Group 5 = {5.1, 5.2}`、`Group 6（root）= {6.1..6.4}`（结合律）
  - 展开后恰好 **8 棵**完整逻辑表达式，与论文正文一致
  - 实现 `MatchGroups` / `MatchExprs` + 缓存 + 可读的**命中证据链**
  - 对照实验：「只比初始表达式的笨办法」vs QO-Verify
  - 缓存规模实验：经典 DP 式连接 memo（8 表时隐含 **17,297,280** 棵完整表达式，而带缓存的算法只走 **569,509** 步）
  - 规则消融：§3.1 的 `join→window` 规则加入前后，结果从 `Unknown` 变 `True`

- **`rules_32_33.py`**
  - §3.2（self-join ⇔ CASE 条件聚合）与 §3.3（被蕴含子查询 ⇔ LOJ + 条件聚合）的 memo 构造与规则注入
  - 两者都复现出「规则不在 → `Unknown`；规则加入 → `True`」

### 2. Apache Calcite（`calcite/`）

在真实优化器上跑真实 SQL，拿真实的 memo。

- 用 `VolcanoPlanner` 为两条 SQL 各生成一份 memo
- **关掉 cost-based pruning**：override `VolcanoPlanner.prune()` 为空实现（对应论文 "turn off cost-based pruning"）
- 只注册**逻辑**规则（不注册物理实现规则），对应论文 "turn off implementation rules"
- **归一化**（对应论文 §2.2 的 "broaden normalization to aid equivalence checking"）：
  1. 剥离「纯列置换」的 `Project`（Calcite 的 `JOIN_COMMUTE` / `JOIN_ASSOCIATE` 会在改写后的 join 上包一层 `Project` 恢复列序，导致两个 memo 永远没有结构完全相同的表达式）
  2. 内连接按「无序对」比较子节点（外连接仍严格保序）
  3. **根列序守卫**：比较前先要求两条查询的输出列名列表逐位相同，否则直接 `Unknown`

### 2b. §3.2 / §3.3 的 `RelOptRule` 实现（`calcite/`）

论文 §3 的三条规则，前两条已实现为**真正的 Calcite 变换规则**，不再是玩具层模拟：

| 文件 | 规则 | 匹配 → 产出 |
|---|---|---|
| `PivotRule.java` | **§3.2** self-join of group-by ⇔ 条件聚合 | `Project-Filter-Join(Agg,Agg)` → `Project-Filter-Aggregate(MAX(CASE),SUM(CASE))` |
| `ScalarAggLojRule.java` | **§3.3** 被蕴含标量聚合子查询 ⇔ LOJ 上的标量聚合 | `Join(Agg_promo, Agg_total)` → `Aggregate(SUM(CASE))(Project)(LeftOuterJoin)` |

验证方式：把规则注册进 `VolcanoPlanner`，看 QO-Verify 的结论是否从 `Unknown` 翻成 `True`：

```
§3.2 Query4(self-join) vs Query5(条件聚合)     规则不在: Unknown   规则在: True
§3.3 Query6(两个标量子查询) vs Query7(LOJ)        规则不在: Unknown   规则在: True
```

#### 实现过程中撞到的四个真实问题（都已记录在代码注释里）

1. **规则描述字符串有格式校验**：`"PivotRule(§3.2 … => …)"` 会被 Calcite 直接拒绝，必须写成 `"PivotRule(project)"` 这种形式。
2. **深层 operand 在已建好的 memo 里匹配不到**：`operand(Project, operand(Filter, operand(Join, …)))` 在规则竞争后往往匹配失败，因为子节点已是 `RelSubset`。最后改成匹配顶层节点 + 在子集合里查找所需节点（`find()`）。
3. **`transformTo` 要求行类型（含可空性）完全一致**：
   ```
   Type mismatch: $f1: INTEGER -> INTEGER NOT NULL
   ```
   规则产出的 `MAX(CASE …)` 是可空的，而 LHS 的列是 `NOT NULL`（join 保证了行存在）。
   **这暴露了一个有意思的局限：Calcite 的类型系统无法表达"join 保证行存在"，所以规则必须显式 `CAST(... AS <LHS 类型>)` 做类型断言**；相应地，检查端的归一化也要能剥掉这种"只改可空性、不改值"的 CAST。
4. **规则之间会互相破坏形状**：我们自己注册的 `FILTER_INTO_JOIN` 会把 `Filter` 折进 join 条件，于是 §3.2 要匹配的 `Filter over Join` 形状消失了。
   实测确认后，§3.2 那组改用不含 `FILTER_INTO_JOIN` 的规则集。
   —— 这是"**规则集相互作用影响覆盖率**"的一个具体例证，和论文 §2.2 的说法一致。
5. （附带）**聚合别名不能进签名**：`s2000=[MAX($1)]` 与 `agg#0=[MAX($1)]` 曾被子结构签名判为不同，导致漏证；已改成只比较聚合函数名 + 参数列表。

### 3. fuzz（`fuzz/`）

SQLite（Python 内置），零依赖。

- **`fuzz_qoverify.py`**：对每一对改写跑 1000 个随机数据库，检验 QO-Verify 的 soundness
  - 数据刻意覆盖：**空表**、大量 **NULL**、**重复行**、**无匹配的 join**、**边界整数**（±2^62）
  - 比较方式：忽略行序、**保留重复行**（bag 语义）
  - 判定列自动读取 Calcite 程序导出的 `results.csv`，不靠手工抄写
- **`fuzz_rules.py`**：对 §3.2 / §3.3 的**前提条件**做破坏性检验

### 4. 结果（`results/`）

所有结果文件都是脚本的**原始输出**，可直接复跑比对。

---

## 结果摘要

### A. Figure 2 的 memo 复现

```
memo Q1 : 6 个 group, 11 个表达式, 隐含 8 棵完整逻辑表达式
  Group 4: 4.1 JOIN[A.x=B.y](G1,G2)   4.2 JOIN[A.x=B.y](G2,G1)      ← 交换律
  Group 5: 5.1 JOIN[B.p=C.q](G2,G3)   5.2 JOIN[B.p=C.q](G3,G2)
  Group 6: 6.1 JOIN[B.p=C.q](G4,G3)   6.2 JOIN[B.p=C.q](G3,G4)      ← 结合律
           6.3 JOIN[A.x=B.y](G5,G1)   6.4 JOIN[A.x=B.y](G1,G5)
```

### B. 缓存的效果（「无命中」最坏情况）

| 表数 | group | 表达式 | 隐含完整表达式 | 无缓存步数 | 有缓存步数 | 加速比 |
|---|---|---|---|---|---|---|
| 4 | 15 | 54 | 120 | 769 | 565 | ×1.4 |
| 5 | 31 | 185 | 1,680 | 6,296 | 3,376 | ×1.9 |
| 6 | 63 | 608 | 30,240 | 57,345 | 19,185 | ×3.0 |
| 7 | 127 | 1,939 | 665,280 | 589,604 | 105,624 | ×5.6 |
| 8 | 255 | 6,058 | **17,297,280** | **>3,000,000**（超预算中止） | **569,509** | — |

### C. Calcite：真实 SQL 的等价检查

| 场景 | 笨办法 | QO-Verify |
|---|---|---|
| S1 同一查询的两种写法（`JOIN…ON` vs 逗号） | Unknown | **True** |
| S2 join 换序（交换律） | Unknown | **True** |
| S3 三表换序（交换 + 结合） | Unknown | **True** |
| S4 合取项顺序不同 | True | True |
| S8 谓词下推（Filter 在 join 上 vs 在输入侧） | Unknown | **True** |
| S7 聚合 + 子查询投影 | Unknown | **True** |
| S5 非等价（谓词常量不同） | Unknown | Unknown |
| S6 非等价（换了连接列） | Unknown | Unknown |
| S9 非等价（CASE 的 `ELSE 0` vs `ELSE NULL`） | Unknown | Unknown |
| S10 输出列序不同（归一化的反向对照） | Unknown | Unknown |
| **合计** | **1/10** | **6/10** |
| **误报** | — | **0** |

**规则消融**：去掉 `JOIN_COMMUTE` / `JOIN_ASSOCIATE` 后，S3 从 `True` 掉回 `Unknown`（6/10 → 5/10）。

**成本结构**：matching 只占 memo 生成时间的 **0–3%**（例如 305 ms vs 4 ms），与论文「成本大头在 memo 生成」一致。

### D. fuzz：soundness（每对 1000 个随机库）

| 场景 | QO-Verify | 反例数 | 结论 |
|---|---|---|---|
| S1 / S2 / S3 / S4 / S8 / S7 | True | **0 / 0 / 0 / 0 / 0 / 0** | 未发现反例 |
| S5 谓词常量不同 | Unknown | 157 | 确认不等价 |
| S6 换了连接列 | Unknown | 131 | 确认不等价 |
| S10 输出列序不同 | Unknown | 306 | 确认不等价（守卫有效） |
| S9 `ELSE 0` vs `ELSE NULL` | Unknown | **587** | 确认不等价 |

> **QO-Verify 判 `True` 的 6 对 × 1000 个随机库 = 6000 次检验，假阳性 0 次。**

### E. §3 三条规则的模拟

| 规则 | 规则集不含 | 规则集含 |
|---|---|---|
| §3.1 join of group-by subqueries ⇔ window functions | Unknown | **True** |
| §3.2 self-join of group-by ⇔ CASE 条件聚合 | Unknown | **True** |
| §3.3 subsumed scalar aggregate ⇔ scalar aggregate over LOJ | Unknown | **True** |

### F. §3.2 / §3.3 的前提条件（破坏性 fuzz）

| 场景 | 反例数 | 结论 |
|---|---|---|
| §3.2 A1 正确改写（`(cust,yr)` 唯一 + HAVING + `IS NOT NULL`） | **0** | 等价 |
| §3.2 A2 去掉 `HAVING` | 722 | 不等价 |
| §3.2 A3 去掉 `a IS NOT NULL` | 28 | 不等价 |
| §3.2 A4 `(cust,yr)` 允许重复 | 16 | 不等价 |
| §3.3 B1 正确改写（pk 唯一 + `ELSE NULL`） | **0** | 等价 |
| §3.3 B2 `CASE` 用 `ELSE 0` | 550 | 不等价 |
| §3.3 B3 `promo.pk` 允许重复 | 11 | 不等价 |

**注意反例率**：`HAVING`（72%）和 `ELSE 0`（55%）一抓就中，但「去掉 `IS NOT NULL`」（2.8%）、「键不唯一」（1.6% / 1.1%）**极难撞上**——这正说明「在单个数据库上实跑结果一致」为什么只是必要条件。

---

### G. §3.2 / §3.3 作为 `RelOptRule`（端到端）

| 目标 | 规则未注册 | **注册规则后** |
|---|---|---|
| §3.2 self-join of group-by ⇔ 条件聚合 | Unknown | **True** |
| §3.3 被蕴含标量子查询 ⇔ LOJ 上的标量聚合 | Unknown | **True** |

（算法一行未改，只往 `VolcanoPlanner` 里加了一条变换规则。）

## 顺带发现的两处论文示例瑕疵

1. **Query 3 缺了 `Filter(C1 IS NOT NULL)`**
   论文自己的 Figure 3(b) 规则里有这个 Filter，但 Query 3 的印刷版没有。若 `l_partkey` 可为 NULL，Query 3 会多出一行 NULL 分组，与 Query 2 不等价。（TPC-H 的 DDL 里 `l_partkey` 是 NOT NULL，所以该例恰好成立。）

2. **Query 7 用 `ELSE 0`，与 Figure 5(b) 的 `ELSE NULL` 不一致**
   当该月没有任何促销销售时，`SUM(空集)` 返回 **NULL**，而 `ELSE 0` 让它变成 **0**。fuzz 实测 **550/1000** 个数据库上结果不同（见 E/B2）。

---

## 快速开始

### 环境

```bash
# Ubuntu 22.04/24.04
sudo apt-get update
sudo apt-get install -y openjdk-21-jdk maven python3
# 国内可用阿里云镜像（见 calcite/settings.xml）
```

### 1) 玩具 Cascades（零依赖）

```bash
cd toy
python3 fig2_qoverify.py      # Figure 2 + 缓存规模 + 规则消融
python3 rules_32_33.py        # §3.2 / §3.3
```

### 2) Calcite

```bash
cd calcite
mvn -q -B -DskipTests compile
mvn -q -B dependency:build-classpath -Dmdep.outputFile=cp.txt

# JDK 17+ 需要 --add-opens
java --add-opens=java.base/java.util=ALL-UNNAMED \
     -cp "target/classes:$(cat cp.txt)" demo.qoverify.QoVerifyDemo

# 消融：去掉 join 交换/结合规则
java --add-opens=java.base/java.util=ALL-UNNAMED \
     -cp "target/classes:$(cat cp.txt)" demo.qoverify.QoVerifyDemo --nojoinrules
```

### 3) fuzz

```bash
cd fuzz
python3 fuzz_qoverify.py 1000    # 10 对 × 1000 个随机库（约 6 秒）
python3 fuzz_rules.py            # §3.2 / §3.3 的前提条件（约 3 秒）
```

> `fuzz_qoverify.py` 会读取 `/root/qoverify/results.csv`（由 Calcite 程序导出的判定结果）。换路径时可用 `-Dqov.csv=<path>` 指定。

### 4) 一键

```bash
bash scripts/run_all.sh
```

---

## 目录结构

```
.
├── README.md
├── toy/
│   ├── fig2_qoverify.py          # Figure 2 + Algorithm 1 + 缓存 + 规则消融(§3.1)
│   └── rules_32_33.py            # §3.2 / §3.3
├── calcite/
│   ├── pom.xml
│   ├── settings.xml              # 阿里云 Maven 镜像
│   └── src/main/java/demo/qoverify/
│       ├── QoVerifyDemo.java      # 主实验：10 个场景 + 归一化 + 规则消融
│       ├── PivotRule.java         # §3.2 的 RelOptRule
│       ├── ScalarAggLojRule.java  # §3.3 的 RelOptRule
│       ├── RuleDemo.java          # 加/不加规则的对照
│       └── DumpPlan.java          # 打印 SQL 转出的逻辑计划（写规则前先用它看形状）
├── fuzz/
│   ├── fuzz_qoverify.py          # soundness（随机库造反例）
│   └── fuzz_rules.py             # §3.2/§3.3 前提条件
├── results/                      # 各脚本的原始输出
└── scripts/
    ├── setup_vm.sh               # 环境准备
    └── run_all.sh                # 一键跑完 Calcite + fuzz
```

---

## 局限与未完成

1. **§3.2 / §3.3 的 `RelOptRule` 是针对特定计划形状写的**（operand 精确到 `Project-Aggregate-Filter-Join-Scan`），已能端到端跑通，但泛化到任意查询形状还需要放宽 operand 与匹配逻辑。
2. **归一化中「内连接按无序对比较」**把比较放宽到「内部列序可置换」，靠根列序守卫兜住。若要更严格的判定，需要在每一步都跟踪列置换。
3. **外连接仍严格保序**，因此涉及 LOJ 重排的等价对（§3.3 那类）目前还证不出。
4. **5 组真实企业库不可得**，用 TPC-H/TPC-DS/JOB 或自建小库替代，因此与论文的绝对数值不可比。
5. **LLM 改写环节未复现**（需要 API key）；本仓库聚焦「验证」而非「生成」。

---

## 引用

```bibtex
@inproceedings{narasayya2026qoverify,
  title     = {Leveraging Query Optimizers to Verify the Soundness of
               LLM-based Query Rewrites for Real-World Workloads, and More!},
  author    = {Narasayya, Vivek and Chaudhuri, Surajit},
  booktitle = {16th Annual Conference on Innovative Data Systems Research (CIDR '26)},
  year      = {2026}
}
```

## License

本仓库代码为独立实现，用于研究复现。论文原文采用 CC-BY 4.0。
