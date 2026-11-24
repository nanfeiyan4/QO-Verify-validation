#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QO-Verify 机制验证 (4)：§3.2 与 §3.3 两条规则
=============================================
沿用 fig2_qoverify 的 Memo / QOVerify，构造两组场景：

  §3.2  Self-join of Group-By  <=>  CASE 条件聚合（TPC-DS Q74 简化）
  §3.3  Subsumed scalar aggregate subquery  <=>  Outer Join 上的标量聚合（Q61 简化）

每一组都做同一件事：
  1) 规则集里**没有**这条规则  -> QO-Verify = Unknown
  2) 把这条规则加进左侧查询的规则集 -> QO-Verify = True
"""
import sys

from fig2_qoverify import Memo, Op, QOVerify, hr


# ============================================================ §3.2
def build_q4():
    """Query 4：对已分组关系 year_total 做 self-join（LHS）"""
    m = Memo("Q4")
    g_sales = m.new_group()
    m.add(g_sales, "S.1", Op("GET", table="sales"))

    # year_total = GBAgg(cust ∪ yr, SUM(amt))(sales)
    g_yt = m.new_group()
    GB = "GB=cust,yr;agg=SUM(amt)"
    m.add(g_yt, "YT.1", Op("GBAgg", extra=GB), (g_sales,))

    # 两份带单边过滤的副本
    g_f1 = m.new_group()
    m.add(g_f1, "F1.1", Op("FILTER", extra="yr=2000"), (g_yt,))
    g_f2 = m.new_group()
    m.add(g_f2, "F2.1", Op("FILTER", extra="yr=2001"), (g_yt,))

    g_root = m.new_group()
    m.add(g_root, "Q4.1", Op("JOIN", frozenset({("R1.cust", "R2.cust")})), (g_f1, g_f2))
    m.root = g_root
    m.initial = m.groups[g_root][0]
    return m


def build_q5():
    """Query 5：一次分组 + 两个条件聚合 + HAVING（RHS）"""
    m = Memo("Q5")
    g_sales = m.new_group()
    m.add(g_sales, "S.1", Op("GET", table="sales"))

    GB = "GB=cust,yr;agg=SUM(amt)"
    g_yt = m.new_group()
    m.add(g_yt, "YT.1", Op("GBAgg", extra=GB), (g_sales,))

    # v1 = GBAgg(cust, MAX(CASE yr=2000), MAX(CASE yr=2001), c1=SUM(CASE..), c2=SUM(CASE..))
    g_v1 = m.new_group()
    m.add(g_v1, "V1.1",
          Op("GBAgg",
             extra="GB=cust;"
                   "f1=MAX(CASE yr=2000 THEN amt);"
                   "f2=MAX(CASE yr=2001 THEN amt);"
                   "c1=SUM(CASE yr=2000 THEN 1 ELSE 0);"
                   "c2=SUM(CASE yr=2001 THEN 1 ELSE 0)"),
          (g_yt,))

    g_root = m.new_group()
    m.add(g_root, "Q5.1", Op("FILTER", extra="cust IS NOT NULL AND c1>0 AND c2>0"), (g_v1,))
    m.root = g_root
    m.initial = m.groups[g_root][0]
    return m


def apply_pivot_rule(m4):
    """把 §3.2 的变换规则装进 Q4 的规则集：
       Join(Filter(b=b1)(GBAgg(a∪b)), Filter(b=b2)(GBAgg(a∪b)))
         -> Filter(a NOT NULL, c1>0, c2>0)( GBAgg(a, f1=MAX(CASE b=b1), f2=MAX(CASE b=b2), c1, c2)( GBAgg(a∪b) ) )
    """
    # 找到 Q4 里的 year_total 组（GBAgg(cust,yr)）
    g_yt = None
    for gid, exprs in m4.groups.items():
        for e in exprs:
            if e.op.kind == "GBAgg" and e.op.extra.startswith("GB=cust,yr"):
                g_yt = gid
                break
    if g_yt is None:
        raise RuntimeError("year_total group not found")

    g_v1 = m4.new_group()
    m4.add(g_v1, "RULE32.V1",
           Op("GBAgg",
              extra="GB=cust;"
                    "f1=MAX(CASE yr=2000 THEN amt);"
                    "f2=MAX(CASE yr=2001 THEN amt);"
                    "c1=SUM(CASE yr=2000 THEN 1 ELSE 0);"
                    "c2=SUM(CASE yr=2001 THEN 1 ELSE 0)"),
           (g_yt,))
    # 规则产出的表达式进 root group（与 Q5 的 root 结构一致）
    m4.add(m4.root, "RULE32",
           Op("FILTER", extra="cust IS NOT NULL AND c1>0 AND c2>0"), (g_v1,))
    return m4


# ============================================================ §3.3
def build_q6():
    """Query 6：两个标量子查询，其中一个是"被蕴含的子集"（LHS）"""
    m = Memo("Q6")
    g_s = m.new_group()
    m.add(g_s, "S.1", Op("GET", table="sales"))
    g_p = m.new_group()
    m.add(g_p, "P.1", Op("GET", table="promotion"))
    g_pf = m.new_group()
    m.add(g_pf, "PF.1", Op("FILTER", extra="ch='Y'"), (g_p,))

    # 子集：sales ⋈ Filter(ch='Y')(promotion)
    g_j = m.new_group()
    m.add(g_j, "J.1", Op("JOIN", frozenset({("s.promo", "p.pk")})), (g_s, g_pf))

    g_a1 = m.new_group()   # 全体
    m.add(g_a1, "A1.1", Op("GBAgg", extra="GB={};agg=SUM(price)"), (g_s,))
    g_a2 = m.new_group()   # 子集
    m.add(g_a2, "A2.1", Op("GBAgg", extra="GB={};agg=SUM(price)"), (g_j,))

    g_root = m.new_group()
    m.add(g_root, "Q6.1", Op("CROSS", extra="scalar x scalar"), (g_a1, g_a2))
    m.root = g_root
    m.initial = m.groups[g_root][0]
    return m


def build_q7():
    """Query 7：LOJ + 条件聚合（RHS）"""
    m = Memo("Q7")
    g_s = m.new_group()
    m.add(g_s, "S.1", Op("GET", table="sales"))
    g_p = m.new_group()
    m.add(g_p, "P.1", Op("GET", table="promotion"))
    g_pf = m.new_group()
    m.add(g_pf, "PF.1", Op("FILTER", extra="ch='Y'"), (g_p,))

    g_loj = m.new_group()
    m.add(g_loj, "LOJ.1", Op("LOJ", frozenset({("s.promo", "p.pk")})), (g_s, g_pf))

    g_root = m.new_group()
    m.add(g_root, "Q7.1",
          Op("GBAgg",
             extra="GB={};"
                   "a1=SUM(price);"
                   "a2=SUM(CASE p.pk IS NOT NULL THEN price ELSE NULL)"),
          (g_loj,))
    m.root = g_root
    m.initial = m.groups[g_root][0]
    return m


def apply_loj_rule(m6):
    """把 §3.3 的变换规则装进 Q6 的规则集：
       CrossProduct(GBAgg({},Agg1(R)), GBAgg({},Agg2(Join(R,F(S)))))
         -> GBAgg({}, Agg1(R.c), Agg2(CASE S.k IS NOT NULL THEN R.c))( LOJ(R, F(S)) )
    """
    # 复用 Q6 里已有的 sales / promotion-filter 组
    g_s = None
    g_pf = None
    for gid, exprs in m6.groups.items():
        for e in exprs:
            if e.op.kind == "GET" and e.op.table == "sales":
                g_s = gid
            if e.op.kind == "FILTER" and e.op.extra == "ch='Y'":
                g_pf = gid
    if g_s is None or g_pf is None:
        raise RuntimeError("sales / promotion-filter group not found")

    g_loj = m6.new_group()
    m6.add(g_loj, "RULE33.LOJ", Op("LOJ", frozenset({("s.promo", "p.pk")})), (g_s, g_pf))
    m6.add(m6.root, "RULE33",
           Op("GBAgg",
              extra="GB={};"
                    "a1=SUM(price);"
                    "a2=SUM(CASE p.pk IS NOT NULL THEN price ELSE NULL)"),
           (g_loj,))
    return m6


# ============================================================ 主流程
def run_case(title, lhs_fn, rhs_fn, rule_fn, rule_name):
    print("\n" + "-" * 78)
    print("  " + title)
    print("-" * 78)

    print("  LHS 的 memo:")
    lhs = lhs_fn()
    for gid in sorted(lhs.groups):
        for e in lhs.groups[gid]:
            print("      G%-2d %r" % (gid, e))
    print("  RHS 的 memo:")
    rhs = rhs_fn()
    for gid in sorted(rhs.groups):
        for e in rhs.groups[gid]:
            print("      G%-2d %r" % (gid, e))

    # 1) 没有规则
    m1, m2 = lhs_fn(), rhs_fn()
    v = QOVerify(m1, m2)
    r1, _ = v.run()
    print("\n  [规则集不含 %s]  QO-Verify => %s" % (rule_name, r1))

    # 2) 加上规则
    m1, m2 = lhs_fn(), rhs_fn()
    rule_fn(m1)
    v = QOVerify(m1, m2)
    r2, ev = v.run()
    print("  [把 %s 加入规则集]  QO-Verify => %s" % (rule_name, r2))
    for line in ev:
        print("      " + line)
    return r1, r2


def main():
    hr("§3.2  Self-join of Group-By  <=>  CASE 条件聚合")
    run_case("Query 4（self-join）  vs  Query 5（条件聚合 + HAVING）",
             build_q4, build_q5, apply_pivot_rule, "§3.2 pivot 规则")

    hr("§3.3  Subsumed scalar aggregate subquery  <=>  Outer Join 上的标量聚合")
    run_case("Query 6（两个标量子查询）  vs  Query 7（LOJ + 条件聚合）",
             build_q6, build_q7, apply_loj_rule, "§3.3 LOJ 规则")

    hr("小结")
    print("""  两条规则都复现出同样的现象：
      * 规则不在规则集里  -> Unknown（算法本身没错，是"证明链断了"）
      * 规则加进去        -> True（算法一行没改）

  §3.2 的关键：GBAgg(a∪b) 使 (a,b) 成为 key，从而 MAX(CASE …) 每组至多一个非 NULL；
               并用 c1/c2 计数复现 inner join 的"两年都得有"，a IS NOT NULL 复现 NULL 语义。
  §3.3 的关键：LOJ 保基数（依赖 FK->PK），S.k 非 NULL 恰好标记"匹配且通过过滤"。
  这两条前提都是**约束相关**的，玩具模型无法表达 —— 下一段用 SQL 级 fuzz 去检验前提被破坏时会怎样。""")


if __name__ == "__main__":
    main()
