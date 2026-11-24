package demo.qoverify;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.tools.RelBuilder;

/**
 * §3.3 Subsumed Scalar Aggregate Subquery <=> Scalar Aggregate over Outer Join（论文 Figure 5）
 *
 * LHS
 *   Join(inner, true)                                    ← 两个标量聚合的笛卡尔积
 *     Aggregate({}, promo = SUM(c))
 *       Project(c) over Filter(S.a)(S) ⋈ R               ← 子集
 *     Aggregate({}, total = SUM(c))
 *       Project(c) over R                                ← 全体
 *
 * RHS
 *   Aggregate({}, promo = SUM(CASE S.k IS NOT NULL THEN c END), total = SUM(c))
 *     Project(CASE(IS NOT NULL(S.k), c, null), c)
 *       LeftOuterJoin(R, S, cond = R.fk = S.pk AND S.a)  ← S.a 从 Filter 移进 ON
 *
 * 前提：R.fk -> S.pk 为外键→主键，LOJ 不使 R 的行数膨胀。
 */
public class ScalarAggLojRule extends RelOptRule {

    public static final ScalarAggLojRule INSTANCE = new ScalarAggLojRule();

    public ScalarAggLojRule() {
        super(operand(LogicalJoin.class,
                operand(LogicalAggregate.class,
                        operand(LogicalProject.class,
                                operand(LogicalFilter.class,
                                        operand(LogicalJoin.class,
                                                operand(LogicalTableScan.class, any()),
                                                operand(LogicalTableScan.class, any()))))),
                operand(LogicalAggregate.class,
                        operand(LogicalProject.class,
                                operand(LogicalTableScan.class, any())))),
                "ScalarAggLojRule(cross-agg-filter-join)");
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        Join top = call.rel(0);
        Aggregate aPromo = call.rel(1);
        Project promoProj = call.rel(2);
        Filter sFilter = call.rel(3);
        Join sub = call.rel(4);
        RelNode r = call.rel(5);
        RelNode s = call.rel(6);
        Aggregate aTotal = call.rel(7);
        Project totalProj = call.rel(8);

        boolean dbg = System.getProperty("qov.debug") != null;

        if (top.getJoinType() != JoinRelType.INNER) {
            return;
        }
        if (sub.getJoinType() != JoinRelType.INNER) {
            return;
        }
        if (aPromo.getGroupCount() != 0 || aTotal.getGroupCount() != 0) {
            return;                                  // 必须是标量聚合
        }
        if (aPromo.getAggCallList().size() != 1 || aTotal.getAggCallList().size() != 1) {
            return;
        }

        int rWidth = r.getRowType().getFieldCount();
        if (s.getRowType().getFieldCount() < 1) {
            return;
        }

        // 连接条件里必须有一对 (R 侧列, S 侧列) 的等值引用 —— 即 FK -> PK
        boolean hasKeyJoin = false;
        for (RexNode c : RelOptUtil.conjunctions(sub.getCondition())) {
            if (c.getKind() != SqlKind.EQUALS || !(c instanceof RexCall)) {
                continue;
            }
            RexCall eq = (RexCall) c;
            RexNode o0 = eq.getOperands().get(0);
            RexNode o1 = eq.getOperands().get(1);
            if (o0 instanceof RexInputRef && o1 instanceof RexInputRef) {
                int i0 = ((RexInputRef) o0).getIndex();
                int i1 = ((RexInputRef) o1).getIndex();
                if ((i0 < rWidth && i1 >= rWidth) || (i1 < rWidth && i0 >= rWidth)) {
                    hasKeyJoin = true;
                }
            }
        }
        if (!hasKeyJoin) {
            return;
        }

        // 两个 Project 必须都把「聚合的第 0 个参数」映到同一列（同一个度量）
        int cPromo = resolve(promoProj, aPromo.getAggCallList().get(0).getArgList().get(0));
        int cTotal = resolve(totalProj, aTotal.getAggCallList().get(0).getArgList().get(0));
        if (cPromo < 0 || cPromo != cTotal) {
            return;
        }

        // ---- 构造 RHS ----
        RelBuilder b = call.builder();
        b.push(r);
        b.push(s);
        b.join(JoinRelType.LEFT, b.and(sub.getCondition(), sFilter.getCondition()));

        RexNode c = b.field(cPromo);
        RexNode sKey = b.field(rWidth);              // S 的第一列 = S.k（PK）
        RexNode nullOfC = b.getRexBuilder().makeNullLiteral(c.getType());
        RexNode caseExpr = b.call(SqlStdOperatorTable.CASE,
                b.call(SqlStdOperatorTable.IS_NOT_NULL, sKey), c, nullOfC);

        b.project(caseExpr, c);
        b.aggregate(b.groupKey(), b.sum(b.field(0)), b.sum(b.field(1)));

        RelNode rhs = b.build();
        if (dbg) {
            System.err.println("[LojRule] lhsType=" + top.getRowType()
                    + "\n" + RelOptUtil.toString(rhs)
                    + "[LojRule] rhsType=" + rhs.getRowType());
        }
        boolean sameType = RelOptUtil.areRowTypesEqual(top.getRowType(), rhs.getRowType(), false);
        if (dbg) {
            System.err.println("[LojRule] sameRowType(ignoreNames)=" + sameType);
        }
        if (!sameType) {
            return;
        }
        call.transformTo(rhs);
    }

    /** Project 的第 i 个输出 → 输入的列下标（要求是纯 RexInputRef） */
    private static int resolve(Project p, int i) {
        if (i < 0 || i >= p.getProjects().size()) {
            return -1;
        }
        RexNode e = p.getProjects().get(i);
        return (e instanceof RexInputRef) ? ((RexInputRef) e).getIndex() : -1;
    }
}
