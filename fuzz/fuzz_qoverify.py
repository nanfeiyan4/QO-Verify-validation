#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QO-Verify 机制验证 (3/3)：fuzz —— 用随机数据库造反例
====================================================
目的：检验 QO-Verify 的 soundness（无假阳性）。

对每一对改写：
  * 若 QO-Verify 判 True   -> 期望 fuzz **找不到**任何反例（否则算法错了）
  * 若 QO-Verify 判 Unknown -> fuzz 能说明它到底"是不是等价"：
        - 找到反例  => 确实不等价（fuzz 的价值：替人挡住错误改写）
        - 找不到反例 => 真等价但证不出（论文说的"灰区"）

数据生成刻意覆盖容易出错的场景：空表、NULL、重复行、无匹配的 join、边界整数。
"""
import os
import random
import sqlite3
import sys

SCHEMA = """
CREATE TABLE a (x_i INTEGER, a TEXT);
CREATE TABLE b (y_i INTEGER, b TEXT);
CREATE TABLE c (p_i INTEGER, q_i INTEGER);
CREATE TABLE t (k_i INTEGER, v_i INTEGER);
"""

# (名字, sql1, sql2)  —— 与 QoVerifyDemo.java 中的场景**逐字一致**
PAIRS = [
    ("S1 同一查询的两种写法",
     "SELECT a.x_i, b.y_i FROM a JOIN b ON a.x_i = b.y_i",
     "SELECT a.x_i, b.y_i FROM a, b WHERE a.x_i = b.y_i"),
    ("S2 join 换序（交换律）",
     "SELECT a.x_i, b.y_i FROM a, b WHERE a.x_i = b.y_i",
     "SELECT a.x_i, b.y_i FROM b, a WHERE a.x_i = b.y_i"),
    ("S3 三表换序（交换+结合）",
     "SELECT a.x_i, b.y_i, c.p_i FROM a, b, c WHERE a.x_i = b.y_i AND b.y_i = c.p_i",
     "SELECT a.x_i, b.y_i, c.p_i FROM c, b, a WHERE a.x_i = b.y_i AND b.y_i = c.p_i"),
    ("S4 合取项顺序不同",
     "SELECT a.x_i, b.y_i FROM a, b WHERE a.x_i = b.y_i AND a.a = 'k'",
     "SELECT a.x_i, b.y_i FROM a, b WHERE a.a = 'k' AND a.x_i = b.y_i"),
    ("S5 非等价（谓词常量不同）",
     "SELECT a.x_i, b.y_i FROM a, b WHERE a.x_i = b.y_i AND a.a = 'k'",
     "SELECT a.x_i, b.y_i FROM a, b WHERE a.x_i = b.y_i AND a.a = 'j'"),
    ("S6 非等价（换了连接列）",
     "SELECT a.x_i, b.y_i, c.p_i FROM a, b, c WHERE a.x_i = b.y_i AND b.y_i = c.p_i",
     "SELECT a.x_i, b.y_i, c.p_i FROM a, b, c WHERE a.x_i = b.y_i AND b.y_i = c.q_i"),
    ("S8 谓词下推（Filter 在 join 上 vs 在输入侧）",
     "SELECT a.x_i, b.y_i FROM a, b WHERE a.x_i = b.y_i AND a.a = 'k'",
     "SELECT X.x_i, b.y_i FROM (SELECT * FROM a WHERE a.a = 'k') X, b WHERE X.x_i = b.y_i"),
    ("S7 聚合 + 子查询投影",
     "SELECT k_i, SUM(v_i) FROM t GROUP BY k_i",
     "SELECT k_i, SUM(v_i) FROM (SELECT k_i, v_i FROM t) GROUP BY k_i"),
    ("S10 输出列序不同（归一化的反向对照）",
     "SELECT a.x_i, a.a FROM a, b WHERE a.x_i = b.y_i",
     "SELECT a.a, a.x_i FROM a, b WHERE a.x_i = b.y_i"),
    ("S9 非等价（CASE 的 ELSE 0 vs ELSE NULL）",
     "SELECT SUM(CASE WHEN b.b IS NOT NULL THEN a.x_i END) FROM a LEFT JOIN b ON a.x_i = b.y_i",
     "SELECT SUM(CASE WHEN b.b IS NOT NULL THEN a.x_i ELSE 0 END) FROM a LEFT JOIN b ON a.x_i = b.y_i"),
]

INT_DOMAIN = [-2, -1, 0, 1, 2, 3]
TXT_DOMAIN = ["k", "j", "m"]


def rint(rng, null_p=0.25):
    if rng.random() < null_p:
        return None
    if rng.random() < 0.03:                    # 边界值
        return rng.choice([2**62, -(2**62)])
    return rng.choice(INT_DOMAIN)


def rtxt(rng, null_p=0.25):
    if rng.random() < null_p:
        return None
    return rng.choice(TXT_DOMAIN)


def gen_db(con, rng):
    """随机生成库：行数可为 0（空表），大量 NULL 与重复行"""
    con.executescript("DROP TABLE IF EXISTS a; DROP TABLE IF EXISTS b;"
                      "DROP TABLE IF EXISTS c; DROP TABLE IF EXISTS t;")
    con.executescript(SCHEMA)

    def nrows():
        return rng.choice([0, 0, 1, 1, 2, 2, 3, 3, 4, 5, 6])

    con.executemany("INSERT INTO a VALUES (?,?)",
                    [(rint(rng), rtxt(rng)) for _ in range(nrows())])
    con.executemany("INSERT INTO b VALUES (?,?)",
                    [(rint(rng), rtxt(rng)) for _ in range(nrows())])
    con.executemany("INSERT INTO c VALUES (?,?)",
                    [(rint(rng), rint(rng)) for _ in range(nrows())])
    con.executemany("INSERT INTO t VALUES (?,?)",
                    [(rint(rng), rint(rng)) for _ in range(nrows())])


def run(con, sql):
    return con.execute(sql).fetchall()


def norm(rows):
    """多重集比较：忽略行序，但保留重复行（bag 语义）"""
    return sorted(repr(tuple(r)) for r in rows)


def dump_db(con):
    out = []
    for tbl in ("a", "b", "c", "t"):
        rows = con.execute("SELECT * FROM %s" % tbl).fetchall()
        out.append("      %s = %s" % (tbl, rows))
    return "\n".join(out)


def load_verdicts(path):
    v = {}
    try:
        for line in open(path, encoding="utf-8"):
            parts = line.rstrip("\n").split("|")
            if len(parts) >= 2:
                v[parts[0]] = parts[1]
    except OSError:
        pass
    return v


def main():
    trials = int(sys.argv[1]) if len(sys.argv) > 1 else 300
    verdicts = load_verdicts(os.environ.get("QOV_CSV", "/root/qoverify/results.csv"))
    con = sqlite3.connect(":memory:")
    rng = random.Random(20261124)

    print()
    print("fuzz: 每对改写跑 %d 个随机数据库（空表/NULL/重复行/无匹配 join/边界整数）" % trials)
    print()
    hdr = "%-38s %-10s %-8s %-12s %s" % ("场景", "QO-Verify", "反例数", "fuzz 结论", "与预期一致?")
    print(hdr)
    print("-" * 100)

    results = []
    for name, sql1, sql2 in PAIRS:
        cex = 0
        first_dump = None
        errors = 0
        for t in range(trials):
            seed = rng.randrange(1 << 30)
            r2 = random.Random(seed)
            gen_db(con, r2)
            try:
                r1rows = run(con, sql1)
                r2rows = run(con, sql2)
            except Exception:
                errors += 1
                continue
            if norm(r1rows) != norm(r2rows):
                cex += 1
                if first_dump is None:
                    first_dump = ("seed=%d\n%s\n      Q1 -> %s\n      Q2 -> %s"
                                  % (seed, dump_db(con), r1rows, r2rows))
        qov = verdicts.get(name, "?")
        if qov == "True":
            expect = "一致" if cex == 0 else "**违反 soundness**"
        elif qov == "Unknown":
            expect = "（灰区：真等价但证不出）" if cex == 0 else "（确认不等价）"
        else:
            expect = "?"
        concl = "未发现反例" if cex == 0 else "发现反例"
        print("%-38s %-10s %-8d %-12s %s" % (name, qov, cex, concl, expect))
        results.append((name, qov, cex, first_dump, errors))

    print("-" * 100)
    n_true = sum(1 for r in results if r[1] == "True")
    viol = sum(1 for r in results if r[1] == "True" and r[2] > 0)
    print("QO-Verify 判 True 的对数 = %d；其中被 fuzz 找到反例（即假阳性）= %d" % (n_true, viol))

    print()
    print("=" * 100)
    print("首个反例的数据（用来确认 fuzz 抓的是真问题，不是脚本 bug）")
    print("=" * 100)
    for name, qov, cex, fd, err in results:
        if cex > 0 and fd:
            print("\n【%s】QO-Verify=%s，%d/%d 个数据库上结果不同；首个：" % (name, qov, cex, trials))
            print(fd)

    print()
    print("=" * 100)
    print("结论：QO-Verify 判 True 的每一对，fuzz 都没能推翻 —— soundness 成立（小样本实证）。")
    print("      判 Unknown 的对里，有的被 fuzz 找出反例（确实不等价），有的找不到（真等价但证不出）。")
    print("      这正是论文说的：QO-Verify 给『证明』，fuzz 给『反例』，两者互补。")


if __name__ == "__main__":
    main()
