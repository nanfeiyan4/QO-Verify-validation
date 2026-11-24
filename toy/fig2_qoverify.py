#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QO-Verify 机制验证 (1/2)：玩具 Cascades
=======================================
目标：
  A. 复现论文 Figure 2 的 memo（6 个 group / 11 个表达式 / 隐含 8 棵完整逻辑表达式）
  B. 复现 Algorithm 1（MatchGroups / MatchExprs），带缓存与可读的命中证据链
  C. 用 5 组场景验证论文的三个主张：
     1) 能证（True 集合非空，且明显多于"只比初始表达式"的笨办法）
     2) 不误证（非等价的 pair 返回 Unknown，绝不返回 True）
     3) 覆盖率由规则集决定（缺规则 -> Unknown；加规则 -> True）
"""
import itertools
import time
import unicodedata


def dispw(s):
    """按东亚字符宽度计算显示宽度（中文占 2 列）"""
    return sum(2 if unicodedata.east_asian_width(c) in ("W", "F") else 1 for c in s)


def pad(s, w):
    return s + " " * max(0, w - dispw(s))

# ---------------------------------------------------------------- 基础结构
class Op:
    """逻辑算子。GET=基表; JOIN=内连接; FILTER=选择; GBAgg=分组聚合; GBAgg_WIN=带窗口聚合的分组聚合"""
    __slots__ = ("kind", "pred", "table", "extra")

    def __init__(self, kind, pred=frozenset(), table="", extra=""):
        self.kind = kind
        self.pred = frozenset(pred)   # 等值谓词的规范化形式：{("A.x","B.y"), ...}
        self.table = table
        self.extra = extra

    def sig(self):
        """结构签名：算子种类 + 算子自身属性（谓词按无序集合规范化）"""
        if self.kind == "GET":
            return ("GET", self.table, self.extra)
        if self.kind == "JOIN":
            return ("JOIN", tuple(sorted(self.pred)))
        return (self.kind, tuple(sorted(self.pred)), self.extra)

    def __repr__(self):
        s = self.kind
        if self.kind == "GET":
            return "Get(%s)" % self.table
        if self.pred:
            s += "[" + " & ".join(sorted("%s=%s" % p for p in self.pred)) + "]"
        if self.extra:
            s += "{%s}" % self.extra
        return s


class Expr:
    __slots__ = ("eid", "op", "children")

    def __init__(self, eid, op, children=()):
        self.eid = eid            # 论文里的 "6.1" 这种编号
        self.op = op
        self.children = tuple(children)   # 子节点是 group id（有序！）

    def __repr__(self):
        c = ("(" + ",".join("G%d" % x for x in self.children) + ")") if self.children else ""
        return "%s %r%s" % (self.eid, self.op, c)


class Memo:
    def __init__(self, name):
        self.name = name
        self.groups = {}       # gid -> [Expr]
        self.next_gid = 1
        self.root = None
        self.initial = None    # 输入查询的"初始逻辑表达式"（用于对照笨办法）

    def new_group(self):
        g = self.next_gid
        self.next_gid += 1
        self.groups[g] = []
        return g

    def add(self, gid, eid, op, children=()):
        for x in self.groups[gid]:                     # memo 内去重
            if x.op.sig() == op.sig() and x.children == tuple(children):
                return x
        e = Expr(eid, op, children)
        self.groups[gid].append(e)
        return e

    # --- 统计"隐含"的完整逻辑表达式个数（这正是隐式枚举所代表的规模） ---
    def count_complete(self, gid, _memo=None):
        memo = _memo if _memo is not None else {}
        if gid in memo:
            return memo[gid]
        total = 0
        for e in self.groups[gid]:
            if not e.children:
                total += 1
            else:
                prod = 1
                for c in e.children:
                    prod *= self.count_complete(c, memo)
                total += prod
        memo[gid] = total
        return total

    def enumerate_complete(self, gid):
        """真正把完整表达式展开出来（只为演示"隐式 vs 显式"的规模差）"""
        out = []
        for e in self.groups[gid]:
            if not e.children:
                out.append((e.op, ()))
            else:
                subs = [self.enumerate_complete(c) for c in e.children]
                for combo in itertools.product(*subs):
                    out.append((e.op, combo))
        return out


def tree_str(node):
    """把展开出来的完整表达式打印成树"""
    op, kids = node
    if op.kind == "GET":
        return op.table
    if not kids:
        return repr(op)
    parts = []
    for k in kids:
        parts.append(tree_str(k))
    return "%s(%s)" % (repr(op), ", ".join(parts))


# ---------------------------------------------------------------- 构造 Figure 2
def build_fig2_memo(name="Q1"):
    """SELECT * FROM A,B,C WHERE A.x=B.y AND B.p=C.q"""
    m = Memo(name)
    g1 = m.new_group(); g2 = m.new_group(); g3 = m.new_group()
    m.add(g1, "1.1", Op("GET", table="A"))
    m.add(g2, "2.1", Op("GET", table="B"))
    m.add(g3, "3.1", Op("GET", table="C"))
    g4 = m.new_group()     # A ⋈ B
    g5 = m.new_group()     # B ⋈ C
    g6 = m.new_group()     # root
    pAB = frozenset({("A.x", "B.y")})
    pBC = frozenset({("B.p", "C.q")})
    # 交换律 -> 同组两条
    m.add(g4, "4.1", Op("JOIN", pAB), (g1, g2))
    m.add(g4, "4.2", Op("JOIN", pAB), (g2, g1))
    m.add(g5, "5.1", Op("JOIN", pBC), (g2, g3))
    m.add(g5, "5.2", Op("JOIN", pBC), (g3, g2))
    # 结合律 -> root 四条
    m.add(g6, "6.1", Op("JOIN", pBC), (g4, g3))   # (A⋈B)⋈C
    m.add(g6, "6.2", Op("JOIN", pBC), (g3, g4))   # C⋈(A⋈B)
    m.add(g6, "6.3", Op("JOIN", pAB), (g5, g1))   # (B⋈C)⋈A
    m.add(g6, "6.4", Op("JOIN", pAB), (g1, g5))   # A⋈(B⋈C)
    m.root = g6
    m.initial = m.groups[g6][0]      # 输入查询的初始表达式 = 6.1
    return m


def build_fig2_memo_reordered(name="Q1'"):
    """语义等价，但写成 FROM A,(B JOIN C)：初始表达式是 A⋈(B⋈C)"""
    m = build_fig2_memo(name)
    m.initial = [e for e in m.groups[m.root] if e.eid == "6.4"][0]
    return m


def build_non_equivalent_memo(name="Q1_bad"):
    """谓词不同：A.x=B.y AND A.x=C.p  -> 与 Q1 不等价"""
    m = Memo(name)
    g1 = m.new_group(); g2 = m.new_group(); g3 = m.new_group()
    m.add(g1, "1.1", Op("GET", table="A"))
    m.add(g2, "2.1", Op("GET", table="B"))
    m.add(g3, "3.1", Op("GET", table="C"))
    g4 = m.new_group(); g5 = m.new_group(); g6 = m.new_group()
    pAB = frozenset({("A.x", "B.y")})
    pAC = frozenset({("A.x", "C.p")})
    m.add(g4, "4.1", Op("JOIN", pAB), (g1, g2))
    m.add(g4, "4.2", Op("JOIN", pAB), (g2, g1))
    m.add(g5, "5.1", Op("JOIN", pAC), (g1, g3))
    m.add(g5, "5.2", Op("JOIN", pAC), (g3, g1))
    m.add(g6, "6.1", Op("JOIN", pAC), (g4, g3))
    m.add(g6, "6.2", Op("JOIN", pAC), (g3, g4))
    m.add(g6, "6.3", Op("JOIN", pAB), (g5, g2))
    m.add(g6, "6.4", Op("JOIN", pAB), (g2, g5))
    m.root = g6
    m.initial = m.groups[g6][0]
    return m


# ---------------------------------------------------------------- Algorithm 1
class QOVerify:
    def __init__(self, m1, m2, use_cache=True, max_steps=None):
        self.m1, self.m2 = m1, m2
        self.use_cache = use_cache
        self.gcache = {}     # (g1,g2) -> bool
        self.ecache = {}     # (eid1,eid2) -> bool
        self.stats = {"g_call": 0, "g_hit": 0, "e_call": 0, "e_hit": 0}
        self.max_steps = max_steps
        self.steps = 0

    # ---- MatchGroups：只要有一对表达式相同即可（∃） ----
    def match_groups(self, g1, g2, ev=None):
        key = (g1, g2)
        if self.use_cache and key in self.gcache:
            self.stats["g_hit"] += 1
            return self.gcache[key]
        self.stats["g_call"] += 1
        res = False
        for e1 in self.m1.groups[g1]:
            for e2 in self.m2.groups[g2]:
                sub = []
                if self.match_exprs(e1, e2, sub):
                    res = True
                    if ev is not None:
                        ev.append("G%d~G%d 命中: %s == %s" % (g1, g2, e1.eid, e2.eid))
                        ev.extend("    " + s for s in sub)
                    break
            if res:
                break
        if self.use_cache:
            self.gcache[key] = res
        return res

    # ---- MatchExprs：算子相同 + arity 相同 + 每个位置的孩子组匹配（∀，且保序） ----
    def match_exprs(self, e1, e2, ev=None):
        self.steps += 1
        if self.max_steps is not None and self.steps > self.max_steps:
            raise RuntimeError("step budget exceeded")
        key = (e1.eid, e2.eid)
        if self.use_cache and key in self.ecache:
            self.stats["e_hit"] += 1
            return self.ecache[key]
        self.stats["e_call"] += 1
        ok = True
        if e1.op.sig() != e2.op.sig():          # 算子 + 算子自身属性（含谓词）
            ok = False
        elif len(e1.children) != len(e2.children):   # arity
            ok = False
        else:
            for i in range(len(e1.children)):
                if not self.match_groups(e1.children[i], e2.children[i], ev):
                    ok = False
                    break
        if self.use_cache:
            self.ecache[key] = ok
        return ok

    def run(self):
        ev = []
        r = self.match_groups(self.m1.root, self.m2.root, ev)
        return ("True" if r else "Unknown"), ev


# ---------------------------------------------------------------- 笨办法对照
def naive_compare(m1, m2):
    """只比较两条查询的"初始逻辑表达式"是否结构相同（不做任何规则推导）"""
    def shape(e):
        return (e.op.sig(), tuple(shape(m1.groups[c][0]) for c in e.children))
    return "True" if shape(m1.initial) == shape(m2.initial) else "Unknown"


# ---------------------------------------------------------------- 大规模 memo（缓存实验用）
def build_join_memo(name, tables, preds, shift=0, leaf_marker=""):
    """经典 DP 式连接 memo：group = 表的子集；表达式 = 每种二切分，且两种顺序都放进去
    （等价于交换律 + 结合律都已作用完）。preds: [(colA, colB), ...]，col 形如 'A.x'
    leaf_marker: 只改叶子算子，用来构造"仅在底部才不同"的对照查询。"""
    m = Memo(name)
    subsets = []
    for k in range(1, len(tables) + 1):
        for c in itertools.combinations(tables, k):
            subsets.append(frozenset(c))
    gid = {}
    for s in subsets:
        gid[s] = m.new_group()
    for t in tables:
        m.add(gid[frozenset([t])], "L.%s" % t, Op("GET", table=t, extra=leaf_marker))
    cnt = [0]

    def eid():
        cnt[0] += 1
        return "E%d" % (cnt[0] + shift)

    for s in subsets:
        if len(s) < 2:
            continue
        ts = sorted(s)
        for k in range(1, len(ts)):
            for left in itertools.combinations(ts, k):
                l = frozenset(left)
                r = s - l
                if not r or min(l) > min(r):
                    continue                     # 每个无序切分只处理一次
                crossing = frozenset(
                    p for p in preds
                    if ((p[0].split(".")[0] in l) != (p[1].split(".")[0] in l))
                )
                m.add(gid[s], eid(), Op("JOIN", crossing), (gid[l], gid[r]))
                m.add(gid[s], eid(), Op("JOIN", crossing), (gid[r], gid[l]))
    m.root = gid[frozenset(tables)]
    m.initial = m.groups[m.root][0]
    return m


# ---------------------------------------------------------------- 场景
def hr(t):
    print("\n" + "=" * 78)
    print(t)
    print("=" * 78)


def dump_memo(m):
    print("memo %-6s : %d 个 group, %d 个表达式, 隐含 %d 棵完整逻辑表达式"
          % (m.name, len(m.groups), sum(len(v) for v in m.groups.values()),
             m.count_complete(m.root)))
    for gid in sorted(m.groups):
        tag = "  (root)" if gid == m.root else ""
        print("  Group %d%s" % (gid, tag))
        for e in m.groups[gid]:
            print("      %r" % (e,))


def main():
    hr("A. 复现 Figure 2 的 memo 结构")
    m1 = build_fig2_memo("Q1")
    dump_memo(m1)
    print("\n把 root group 的 4 条表达式分别展开（显式枚举，仅用于对比规模）：")
    # 手工展开：root 的每条表达式 × 子组的备选
    trees = []
    for e in m1.groups[m1.root]:
        subs = [m1.enumerate_complete(c) for c in e.children]
        for combo in itertools.product(*subs):
            trees.append((e.op, combo))
    for i, t in enumerate(trees, 1):
        print("  #%d %s" % (i, tree_str(t)))
    print("  => %d 棵完整表达式，来自 %d 个表达式对象（论文的 compactness 论点）"
          % (len(trees), sum(len(v) for v in m1.groups.values())))

    hr("B. 场景验证")

    scenarios = []

    # S1 同一查询
    scenarios.append(("S1  Q1 vs Q1（自身）", build_fig2_memo("Q1"), build_fig2_memo("Q1")))

    # S2 等价但写法不同（初始表达式分别是 6.1 与 6.4）
    scenarios.append(("S2  Q1 vs Q1'（join 顺序不同，语义等价）",
                      build_fig2_memo("Q1"), build_fig2_memo_reordered("Q1'")))

    # S3 非等价（谓词不同）
    scenarios.append(("S3  Q1 vs Q1_bad（谓词不同，非等价）",
                      build_fig2_memo("Q1"), build_non_equivalent_memo("Q1_bad")))

    print(pad("场景", 50) + pad("笨办法", 10) + "QO-Verify")
    print("-" * 78)
    detail = {}
    for label, a, b in scenarios:
        v = QOVerify(a, b, use_cache=True)
        res, ev = v.run()
        detail[label] = (v, ev)
        print(pad(label, 50) + pad(naive_compare(a, b), 10) + res)

    print("\n【S2 的命中证据链】")
    for line in detail["S2  Q1 vs Q1'（join 顺序不同，语义等价）"][1]:
        print("   " + line)

    print("\n【S3 为什么是 Unknown】两边的 join 谓词集合不同（{A.x=B.y,B.p=C.q} vs {A.x=B.y,A.x=C.p}），"
          "\n     root group 里不存在签名相同的表达式 → 算法诚实地返回 Unknown，而不是猜测。")

    hr("C. 缓存的效果（论文效率第 4 条）：'无命中'最坏情况下的规模实验")
    print("  构造经典 DP 式连接 memo（group = 表的子集，表达式 = 每种二切分 + 交换律两条），")
    print("  再构造一条**不等价**的查询让匹配必然走完整个乘积（Unknown 即上界情形）。\n")
    print(pad("表数", 6) + pad("group", 8) + pad("表达式", 10) + pad("隐含完整表达式", 22)
          + pad("无缓存步数", 14) + pad("有缓存步数", 14) + "加速比")
    print("-" * 96)
    for n in (4, 5, 6, 7, 8):
        tables = [chr(ord("A") + i) for i in range(n)]
        preds1 = [(tables[i] + ".x", tables[i + 1] + ".x") for i in range(n - 1)]
        a = build_join_memo("J%d" % n, tables, preds1, shift=0, leaf_marker="")
        # 只在叶子不同 => join 层结构完全一致，递归必须一路走到底，无任何提前剪枝
        b = build_join_memo("J%dbad" % n, tables, preds1, shift=10000, leaf_marker="'")
        n_groups = len(a.groups)
        n_exprs = sum(len(v) for v in a.groups.values())
        n_full = a.count_complete(a.root)
        row = []
        for cache in (False, True):
            v = QOVerify(a, b, use_cache=cache, max_steps=3000000)
            try:
                t0 = time.time()
                res, _ = v.run()
                dt = time.time() - t0
                row.append(("%d" % (v.stats["g_call"] + v.stats["e_call"]), dt, res))
            except RuntimeError:
                row.append((">3,000,000", float("nan"), "ABORT"))
        speed = "n/a"
        if row[0][0].isdigit() and row[1][0].isdigit() and int(row[1][0]) > 0:
            speed = "x%.1f" % (int(row[0][0]) / int(row[1][0]))
        print(pad(str(n), 6) + pad(str(n_groups), 8) + pad(str(n_exprs), 10)
              + pad("{:,}".format(n_full), 22)
              + pad(row[0][0], 14) + pad(row[1][0], 14) + speed)
    print("\n  注：'无缓存步数' 对应论文说的 'straightforward 实现会非常昂贵'；")
    print("      '有缓存步数' 由两个 memo 的规模决定（多项式），与完整表达式数（指数）无关。")

    hr("D. 规则集消融：缺规则 -> Unknown，补规则 -> True（覆盖率的真正决定因素）")
    rule_ablation()

    hr("E. 结论")
    print("""  1) memo 结构复现成功：6 个 group / 11 个表达式 / 隐含 8 棵完整逻辑表达式。
  2) Algorithm 1 行为与论文一致：
       - S1/S2 返回 True（S2 的初始表达式不同，笨办法只能给 Unknown）；
       - S3 返回 Unknown，且没有误报 True；
       - 匹配是"结构相同"的严格判据（算子 + 谓词 + arity + 有序列孩子）。
  3) 缓存把最坏情况（无命中）的重复比较消掉：见 C 段调用次数对比。
  4) D 段证明：覆盖率是"规则集的函数"，不是"算法的函数"—— 这正是论文 §3 的立论。""")


# ---------------------------------------------------------------- E4 规则消融
def build_q2_query3():
    """§3.1：Query2（两个分组子查询 join） vs Query3（窗口函数）"""
    # --- Q2 = Query 2 的 memo ---
    m2 = Memo("Q2")
    g_li = m2.new_group()
    m2.add(g_li, "L.1", Op("GET", table="lineitem"))
    g_d1 = m2.new_group()   # GROUP BY l_partkey
    g_d2 = m2.new_group()   # GROUP BY l_partkey, l_suppkey
    m2.add(g_d1, "D1.1", Op("GBAgg", extra="GB=l_partkey;agg=SUM(l_quantity)"), (g_li,))
    m2.add(g_d2, "D2.1", Op("GBAgg", extra="GB=l_partkey,l_suppkey;agg=SUM(l_quantity)"), (g_li,))
    g_root2 = m2.new_group()
    m2.add(g_root2, "2.1", Op("JOIN", frozenset({("D1.l_partkey", "D2.l_partkey")})), (g_d2, g_d1))
    m2.root = g_root2
    m2.initial = m2.groups[g_root2][0]

    # --- Q3 = Query 3 的 memo（窗口函数形态，是"另一种算子"） ---
    m3 = Memo("Q3")
    g_li3 = m3.new_group()
    m3.add(g_li3, "L.1", Op("GET", table="lineitem"))
    g_f = m3.new_group()
    m3.add(g_f, "F.1", Op("FILTER", extra="l_partkey IS NOT NULL"), (g_li3,))
    g_root3 = m3.new_group()
    m3.add(g_root3, "3.1",
           Op("GBAgg_WIN", extra="GB=l_partkey,l_suppkey;agg=SUM OVER(l_partkey);agg1=SUM OVER(l_partkey,l_suppkey)"),
           (g_f,))
    m3.root = g_root3
    m3.initial = m3.groups[g_root3][0]
    return m2, m3


def apply_window_rule(m2):
    """把 §3.1 的变换规则"装进" Q2 的规则集：
    Join(GBAgg(C), GBAgg(C1)) -> GBAgg_WIN(Filter(C1 IS NOT NULL))(R)
    实现方式：在 memo 里新建/复用 Filter 组，并把窗口形态的表达式加入 root group。
    """
    g_li = 1   # build_q2_query3 中第一个建立的 group 就是 lineitem
    # 复用或新建 Filter 组
    g_f = None
    for gid, exprs in m2.groups.items():
        for e in exprs:
            if e.op.kind == "FILTER" and e.op.extra == "l_partkey IS NOT NULL":
                g_f = gid
                break
    if g_f is None:
        g_f = m2.new_group()
        m2.add(g_f, "F.1", Op("FILTER", extra="l_partkey IS NOT NULL"), (g_li,))
    m2.add(m2.root, "RULE.3.1",
           Op("GBAgg_WIN", extra="GB=l_partkey,l_suppkey;agg=SUM OVER(l_partkey);agg1=SUM OVER(l_partkey,l_suppkey)"),
           (g_f,))
    return m2


def rule_ablation():
    print("  §3.1 场景：Query 2（两个分组子查询 join） vs Query 3（窗口函数）\n")

    m2, m3 = build_q2_query3()
    print("  --- 规则集不含 §3.1 规则 ---")
    v = QOVerify(m2, m3)
    res, _ = v.run()
    print("      QO-Verify => %s" % res)
    print("      Q2 root 的表达式: %s" % [e.eid for e in m2.groups[m2.root]])
    print("      Q3 root 的表达式: %s" % [e.eid for e in m3.groups[m3.root]])

    print("\n  --- 把 §3.1 规则加入 Q2 的规则集后 ---")
    m2, m3 = build_q2_query3()
    apply_window_rule(m2)
    v = QOVerify(m2, m3)
    res, ev = v.run()
    print("      QO-Verify => %s" % res)
    for line in ev:
        print("      " + line)
    print("\n      说明：Q2 的 memo 里出现了一条与 Q3 root 同签名、同结构孩子的表达式")
    print("            （RULE.3.1 == 3.1），于是两侧求交成功。")
    print("            —— 覆盖率从 0/1 变成 1/1，而算法一行都没改。")


if __name__ == "__main__":
    main()
