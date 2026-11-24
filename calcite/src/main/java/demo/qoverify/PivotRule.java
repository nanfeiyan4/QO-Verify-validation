package demo.qoverify;

import java.util.ArrayList;
import java.util.List;

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
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.tools.RelBuilder;

/**
 * §3.2 Self-join of Group-By <=> CASE 条件聚合（论文 Figure 4）
 *
 * LHS                              RHS
 *   Project(a,f1,f2)                 Project(a,f1,f2)
 *     Filter(a=a ∧ b=b1 ∧ b=b2)        Filter(c1>0 ∧ c2>0)
 *       Join(inner)                      Aggregate(a, MAX(CASE b=b1), MAX(CASE b=b2),
 *         Aggregate(a∪b, f)(R)                          SUM(CASE b=b1), SUM(CASE b=b2))
 *         Aggregate(a∪b, f)(R)             Project(a, CASE b=b1, CASE b=b2, CASE b=b1 1/0, CASE b=b2 1/0)
 *                                            Aggregate(a∪b, f)(R)
 *
 * 前提：两侧是同一个 (a ∪ b) 上的同一个聚合，即 (a,b) 是 R 的 key。
 */
public class PivotRule extends RelOptRule {

    public static final PivotRule INSTANCE = new PivotRule();

    public PivotRule() {
        super(operand(LogicalProject.class, any()), "PivotRule(project)");
    }

    private static final class Constraint {
        final int groupIdxInAgg;
        final Comparable<?> value;
        Constraint(int i, Comparable<?> v) {
            groupIdxInAgg = i;
            value = v;
        }
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        Project project = call.rel(0);
        // 从子集合里找出所需节点（memo 里子节点是 RelSubset）
        Filter filter = (Filter) find(project.getInput(), LogicalFilter.class);
        if (filter == null) {
            return;
        }
        Join join = (Join) find(filter.getInput(), LogicalJoin.class);
        if (join == null) {
            return;
        }
        Aggregate left = (Aggregate) find(join.getLeft(), LogicalAggregate.class);
        Aggregate right = (Aggregate) find(join.getRight(), LogicalAggregate.class);
        if (left == null || right == null) {
            return;
        }

        boolean dbg = System.getProperty("qov.debug") != null;
        if (dbg) {
            System.err.println("[PivotRule] onMatch reached; joinType=" + join.getJoinType());
        }
        if (join.getJoinType() != JoinRelType.INNER) {
            return;
        }
        boolean same = left.deepEquals(right);
        if (dbg) {
            System.err.println("[PivotRule] left.deepEquals(right)=" + same
                    + "  groupCount=" + left.getGroupCount()
                    + "  aggCalls=" + left.getAggCallList().size());
        }
        if (!same) {
            return;                                  // 必须是同一个 (a∪b) 上的聚合
        }
        if (left.getGroupCount() != 2 || left.getAggCallList().size() != 1) {
            return;
        }

        int leftWidth = left.getRowType().getFieldCount();
        List<Constraint> lc = new ArrayList<>();
        List<Constraint> rc = new ArrayList<>();
        for (RexNode c : RelOptUtil.conjunctions(filter.getCondition())) {
            if (c.getKind() != SqlKind.EQUALS || !(c instanceof RexCall)) {
                continue;
            }
            RexCall eq = (RexCall) c;
            RexNode x = eq.getOperands().get(0);
            RexNode y = eq.getOperands().get(1);
            if (x instanceof RexInputRef && y instanceof RexLiteral) {
                collect((RexInputRef) x, (RexLiteral) y, leftWidth, left, lc, rc);
            } else if (y instanceof RexInputRef && x instanceof RexLiteral) {
                collect((RexInputRef) y, (RexLiteral) x, leftWidth, left, lc, rc);
            }
        }
        if (lc.size() != 1 || rc.size() != 1) {
            return;
        }
        Constraint cl = lc.get(0);
        Constraint cr = rc.get(0);
        if (cl.value.equals(cr.value)) {
            return;                                  // b1 必须 != b2
        }

        RelBuilder b = call.builder();
        b.push(left);                                // Aggregate(a ∪ b, f)

        int bIdx = cl.groupIdxInAgg;
        int fIdx = leftWidth - 1;
        RexNode bField = b.field(bIdx);
        RexNode fField = b.field(fIdx);
        RexNode nullOfF = b.getRexBuilder().makeNullLiteral(fField.getType());

        RexNode case1 = b.call(SqlStdOperatorTable.CASE,
                b.equals(bField, b.literal(cl.value)), fField, nullOfF);
        RexNode case2 = b.call(SqlStdOperatorTable.CASE,
                b.equals(bField, b.literal(cr.value)), fField, nullOfF);
        RexNode cnt1 = b.call(SqlStdOperatorTable.CASE,
                b.equals(bField, b.literal(cl.value)), b.literal(1), b.literal(0));
        RexNode cnt2 = b.call(SqlStdOperatorTable.CASE,
                b.equals(bField, b.literal(cr.value)), b.literal(1), b.literal(0));

        List<RexNode> proj = new ArrayList<>();
        for (int i = 0; i < left.getGroupCount(); i++) {
            proj.add(b.field(i));
        }
        proj.add(case1);
        proj.add(case2);
        proj.add(cnt1);
        proj.add(cnt2);
        b.project(proj);

        int aIdx = (bIdx == 0) ? 1 : 0;
        int pBase = left.getGroupCount();
        b.aggregate(b.groupKey(aIdx),
                b.max(b.field(pBase)),
                b.max(b.field(pBase + 1)),
                b.sum(b.field(pBase + 2)),
                b.sum(b.field(pBase + 3)));

        RexNode g1 = b.call(SqlStdOperatorTable.GREATER_THAN, b.field(3), b.literal(0));
        RexNode g2 = b.call(SqlStdOperatorTable.GREATER_THAN, b.field(4), b.literal(0));
        b.filter(b.and(g1, g2));
        b.project(b.field(0), b.field(1), b.field(2));

        // Calcite 的类型系统无法表达"join 保证行存在"，所以规则产出的列是可空的，
        // 而 LHS 的列是 NOT NULL。这里把列 CAST 成 LHS 的确切类型（值不变，只是类型断言）。
        List<RexNode> out = new ArrayList<>();
        for (int i = 0; i < project.getRowType().getFieldCount(); i++) {
            RelDataType target = project.getRowType().getFieldList().get(i).getType();
            out.add(b.getRexBuilder().makeCast(target, b.field(i)));
        }
        b.project(out);

        RelNode rhs = b.build();
        if (dbg) {
            System.err.println("[PivotRule] lhsType=" + project.getRowType()
                    + "\n" + RelOptUtil.toString(rhs)
                    + "[PivotRule] rhsType=" + rhs.getRowType());
        }
        boolean sameType = sameSqlTypes(project.getRowType(), rhs.getRowType());
        if (dbg) {
            System.err.println("[PivotRule] sameRowType(ignoreNames)=" + sameType);
        }
        if (!sameType) {
            return;
        }
        if (dbg) {
            System.err.println("[PivotRule] calling transformTo");
        }
        try {
            call.transformTo(rhs);
            if (dbg) {
                System.err.println("[PivotRule] transformTo done");
            }
        } catch (RuntimeException ex) {
            if (dbg) {
                System.err.println("[PivotRule] transformTo FAILED: " + ex);
            }
        }
    }

    /** 只比较各列的 SQL 类型（忽略列名与可空性） */
    private static boolean sameSqlTypes(org.apache.calcite.rel.type.RelDataType a,
                                        org.apache.calcite.rel.type.RelDataType b) {
        if (a.getFieldCount() != b.getFieldCount()) {
            return false;
        }
        for (int i = 0; i < a.getFieldCount(); i++) {
            String ta = a.getFieldList().get(i).getType().getSqlTypeName().getName();
            String tb = b.getFieldList().get(i).getType().getSqlTypeName().getName();
            if (!ta.equals(tb)) {
                return false;
            }
        }
        return true;
    }

    /** 在（可能被 RelSubset 包住的）子树里查找第一个符合类型的节点 */
    private static RelNode find(RelNode n, Class<?> cls) {
        if (cls.isInstance(n)) {
            return n;
        }
        if (n instanceof org.apache.calcite.plan.volcano.RelSubset) {
            for (RelNode m : ((org.apache.calcite.plan.volcano.RelSubset) n).getRelList()) {
                RelNode r = find(m, cls);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static void collect(RexInputRef ref, RexLiteral lit, int leftWidth, Aggregate agg,
                                List<Constraint> lc, List<Constraint> rc) {
        int i = ref.getIndex();
        int gi;
        if (i < leftWidth) {
            gi = (i < agg.getGroupCount()) ? i : -1;
            if (gi >= 0) {
                lc.add(new Constraint(gi, lit.getValueAs(Comparable.class)));
            }
        } else {
            int j = i - leftWidth;
            gi = (j < agg.getGroupCount()) ? j : -1;
            if (gi >= 0) {
                rc.add(new Constraint(gi, lit.getValueAs(Comparable.class)));
            }
        }
    }
}
