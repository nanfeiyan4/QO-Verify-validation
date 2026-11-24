#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
§3.2 / §3.3 的 SQL 级 fuzz：验证"规则本身"以及"规则的前提条件"
=============================================================
玩具模型只能证明"加规则 -> Unknown 变 True"，
但两条规则的正确性都依赖**约束条件**，玩具表达不了。这里用真实 SQL 验证：

§3.2（self-join <=> 条件聚合）的前提：
    P1  (a,b) 是 R 的 key              —— 破坏它，规则失效
    P2  HAVING 的计数（两年都得有）      —— 去掉它，规则失效
    P3  a IS NOT NULL                  —— 去掉它，NULL 客户会多出一行

§3.3（被蕴含子查询 <=> LOJ + 条件聚合）的前提：
    Q1  连接是 FK->PK（S.pk 唯一）       —— 破坏它，LOJ 乘行，规则失效
    Q2  CASE 用 ELSE NULL（而非 ELSE 0） —— 换成 ELSE 0，空集会出 NULL/0 之差
"""
import random
import sqlite3

TRIALS = 1000

# ------------------------------------------------------------------ 3.2
SCHEMA_A = """
CREATE TABLE sales (cust TEXT, yr INTEGER, amt INTEGER);
"""


def gen_a(con, rng, unique_key):
    con.executescript("DROP TABLE IF EXISTS sales;")
    con.executescript(SCHEMA_A)
    rows = []
    n = rng.choice([0, 0, 1, 2, 3, 3, 4, 5, 6])
    seen = set()
    for _ in range(n):
        cust = rng.choice(["c1", "c2", "c3", None])
        yr = rng.choice([2000, 2001, 2002, None])
        amt = rng.choice([-1, 0, 1, 2, None])
        if unique_key:
            k = (cust, yr)
            if k in seen:
                continue
            seen.add(k)
        rows.append((cust, yr, amt))
    con.executemany("INSERT INTO sales VALUES (?,?,?)", rows)


Q4 = """
SELECT t1.cust, t1.amt, t2.amt
FROM sales t1, sales t2
WHERE t1.cust = t2.cust AND t1.yr = 2000 AND t2.yr = 2001
"""

Q5 = """
SELECT cust,
       MAX(CASE WHEN yr = 2000 THEN amt END) AS s2000,
       MAX(CASE WHEN yr = 2001 THEN amt END) AS s2001
FROM sales
WHERE cust IS NOT NULL
GROUP BY cust
HAVING SUM(CASE WHEN yr = 2000 THEN 1 ELSE 0 END) > 0
   AND SUM(CASE WHEN yr = 2001 THEN 1 ELSE 0 END) > 0
"""

Q5_NO_HAVING = """
SELECT cust,
       MAX(CASE WHEN yr = 2000 THEN amt END) AS s2000,
       MAX(CASE WHEN yr = 2001 THEN amt END) AS s2001
FROM sales
WHERE cust IS NOT NULL
GROUP BY cust
"""

Q5_NO_ISNOTNULL = """
SELECT cust,
       MAX(CASE WHEN yr = 2000 THEN amt END) AS s2000,
       MAX(CASE WHEN yr = 2001 THEN amt END) AS s2001
FROM sales
GROUP BY cust
HAVING SUM(CASE WHEN yr = 2000 THEN 1 ELSE 0 END) > 0
   AND SUM(CASE WHEN yr = 2001 THEN 1 ELSE 0 END) > 0
"""

# ------------------------------------------------------------------ 3.3
SCHEMA_B = """
CREATE TABLE s (promo INTEGER, price INTEGER);
CREATE TABLE promo (pk INTEGER, ch TEXT);
"""


def gen_b(con, rng, pk_unique):
    con.executescript("DROP TABLE IF EXISTS s; DROP TABLE IF EXISTS promo;")
    con.executescript(SCHEMA_B)
    n = rng.choice([0, 0, 1, 2, 3, 3, 4, 5, 6])
    con.executemany("INSERT INTO s VALUES (?,?)",
                    [(rng.choice([1, 2, 3, None]), rng.choice([-1, 0, 1, 2, None]))
                     for _ in range(n)])
    m = rng.choice([0, 0, 1, 2, 3, 3, 4])
    if pk_unique:
        pks = rng.sample([1, 2, 3], k=min(m, 3))
        prows = [(pk, rng.choice(["Y", "N", None])) for pk in pks]
    else:
        prows = [(rng.choice([1, 2, 3, None]), rng.choice(["Y", "N", None]))
                 for _ in range(m)]
    con.executemany("INSERT INTO promo VALUES (?,?)", prows)


Q6 = """
SELECT
  (SELECT SUM(price) FROM s JOIN promo ON s.promo = promo.pk WHERE promo.ch = 'Y'),
  (SELECT SUM(price) FROM s)
"""

Q7 = """
SELECT
  SUM(CASE WHEN p.pk IS NOT NULL THEN s.price END),
  SUM(s.price)
FROM s LEFT JOIN promo p ON s.promo = p.pk AND p.ch = 'Y'
"""

Q7_ELSE0 = """
SELECT
  SUM(CASE WHEN p.pk IS NOT NULL THEN s.price ELSE 0 END),
  SUM(s.price)
FROM s LEFT JOIN promo p ON s.promo = p.pk AND p.ch = 'Y'
"""


# ------------------------------------------------------------------ 公共
def norm(rows):
    return sorted(repr(tuple(r)) for r in rows)


def dump(con, tables):
    return "\n".join("      %s = %s" % (t, con.execute("SELECT * FROM %s" % t).fetchall())
                     for t in tables)


def run_case(name, schema, gen, sql1, sql2, tables, seed):
    con = sqlite3.connect(":memory:")
    rng = random.Random(seed)
    cex = 0
    first = None
    for _ in range(TRIALS):
        gen(con, random.Random(rng.randrange(1 << 30)))
        a = norm(con.execute(sql1).fetchall())
        b = norm(con.execute(sql2).fetchall())
        if a != b:
            cex += 1
            if first is None:
                first = ("%s\n      Q_lhs -> %s\n      Q_rhs -> %s"
                         % (dump(con, tables), a, b))
    con.close()
    return name, cex, first


CASES = [
    # (名字, schema, 生成器, sql1, sql2, 表名, 期望)
    ("§3.2 A1 正确改写（(cust,yr) 唯一，含 HAVING 与 IS NOT NULL）",
     SCHEMA_A, lambda c, r: gen_a(c, r, True), Q4, Q5, ["sales"], "等价"),
    ("§3.2 A2 破坏 P2：去掉 HAVING",
     SCHEMA_A, lambda c, r: gen_a(c, r, True), Q4, Q5_NO_HAVING, ["sales"], "不等价"),
    ("§3.2 A3 破坏 P3：去掉 a IS NOT NULL",
     SCHEMA_A, lambda c, r: gen_a(c, r, True), Q4, Q5_NO_ISNOTNULL, ["sales"], "不等价"),
    ("§3.2 A4 破坏 P1：(cust,yr) 允许重复",
     SCHEMA_A, lambda c, r: gen_a(c, r, False), Q4, Q5, ["sales"], "不等价"),

    ("§3.3 B1 正确改写（pk 唯一，CASE 用 ELSE NULL）",
     SCHEMA_B, lambda c, r: gen_b(c, r, True), Q6, Q7, ["s", "promo"], "等价"),
    ("§3.3 B2 破坏 Q2：CASE 用 ELSE 0",
     SCHEMA_B, lambda c, r: gen_b(c, r, True), Q6, Q7_ELSE0, ["s", "promo"], "不等价"),
    ("§3.3 B3 破坏 Q1：promo.pk 允许重复",
     SCHEMA_B, lambda c, r: gen_b(c, r, False), Q6, Q7, ["s", "promo"], "不等价"),
]


def main():
    print()
    print("§3.2 / §3.3 SQL 级 fuzz：每例 %d 个随机数据库" % TRIALS)
    print()
    print("%-52s %-8s %-10s %s" % ("场景", "反例数", "实测", "预期"))
    print("-" * 96)
    results = []
    for i, (name, schema, gen, s1, s2, tables, expect) in enumerate(CASES):
        n, cex, first = run_case(name, schema, gen, s1, s2, tables, seed=7000 + i)
        got = "等价" if cex == 0 else "不等价"
        flag = "OK" if got == expect else "**不符**"
        print("%-52s %-8d %-10s %s  %s" % (name, cex, got, expect, flag))
        results.append((name, cex, first))

    print()
    print("=" * 96)
    print("反例样本（确认 fuzz 抓的是真问题）")
    print("=" * 96)
    for name, cex, first in results:
        if cex > 0 and first:
            print("\n【%s】%d/%d 个库结果不同；首个：" % (name, cex, TRIALS))
            print(first)

    print()
    print("=" * 96)
    print("结论：")
    print("  §3.2 的前提 (a,b) 是 key、HAVING 计数、a IS NOT NULL —— 三者缺一即不等价；")
    print("       三者在时 fuzz 找不到任何反例。")
    print("  §3.3 的前提 FK->PK（pk 唯一）与 CASE 用 ELSE NULL —— 缺一即不等价；")
    print("       两者在时 fuzz 找不到任何反例。")
    print("  => 论文说的『规则是条件保序的』在两条规则上都得到了实证。")


if __name__ == "__main__":
    main()
