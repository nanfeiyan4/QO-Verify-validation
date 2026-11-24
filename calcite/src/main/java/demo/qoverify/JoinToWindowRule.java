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
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.tools.RelBuilder;

/**
 * §3.1 Join of Group-By subqueries <=> Window functions（论文 Figure 3）
 *
 * LHS
 *   Project(p, s, q1, q2)
 *     Filter(C1 = C1)
 *       Join(inner)
 *         Aggregate(C1, agg(c))  over Project over R     ← 粗粒度
 *         Project
 *           Aggregate(C, agg(c)) over R                  ← 细粒度，C1 ⊂ C
 *
 * RHS
 *   Aggregate(group = 全部输出列)                        ← DISTINCT（对应 GBAgg 的去重）
 *     Project(agg(c) OVER (PARTITION BY C1),
 *             agg(c) OVER (PARTITION BY C))
 *       Filter(C1 IS NOT NULL)(R)                       ← 复现 inner join 丢弃 NULL
 *
 * 前提：C1 ⊆ C，join 是 C1 上的多对一连接。
 */
public class JoinToWindowRule extends RelOptRule {

    public static final JoinToWindowRule INSTANCE = new JoinToWindowRule();

    public JoinToWindowRule() {
        super(operand(LogicalProject.class,
                operand(LogicalFilter.class,
                        operand(LogicalJoin.class,
                                operand(LogicalAggregate.class,
                                        operand(LogicalProject.class,
                                                operand(LogicalTableScan.class, any()))),
                                operand(LogicalProject.class,
                                        operand(LogicalAggregate.class,
                                                operand(LogicalTableScan.class, any())))))),
                "JoinToWindowRule(project-filter-join-agg-agg)");
    }

    private static int resolveArg(Project p, int i) {
        if (p == null) {
            return i;
        }
        RexNode e = p.getProjects().get(i);
        return (e instanceof RexInputRef) ? ((RexInputRef) e).getIndex() : i;
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        Project project = call.rel(0);
        Filter filter = call.rel(1);
        Join join = call.rel(2);
        Aggregate coarse = call.rel(3);
        Project coarseProj = call.rel(4);
        Project fineProj = call.rel(6);
        Aggregate fine = call.rel(7);

        boolean dbg = System.getProperty("qov.debug") != null;
        if (dbg) {
            System.err.println("[WindowRule] matched");
        }

        if (join.getJoinType() != JoinRelType.INNER) {
            return;
        }
        if (coarse.getGroupCount() != 1 || fine.getGroupCount() != 2) {
            return;
        }
        if (coarse.getAggCallList().size() != 1 || fine.getAggCallList().size() != 1) {
            return;
        }
        int c1Idx = coarse.getGroupSet().nth(0);
        if (fine.getGroupSet().nth(0) != c1Idx) {
            return;                                   // C1 必须是 C 的第一个分组列
        }
        int c2Idx = fine.getGroupSet().nth(1);

        // 两个聚合作用在同一张表上
        RelNode r = fine.getInput();
        if (!(r instanceof LogicalTableScan)
                && !(r instanceof LogicalProject)
                && !(r instanceof org.apache.calcite.plan.volcano.RelSubset)) {
            return;
        }
        int qtyIdx = resolveArg(fineProj, fine.getAggCallList().get(0).getArgList().get(0));
        RelDataType aggType = fine.getAggCallList().get(0).getType();

        // ---- 构造 RHS ----
        RelBuilder b = call.builder();
        RexBuilder rb = b.getRexBuilder();

        b.push(r);
        RexNode c1Field = b.field(c1Idx);
        b.filter(rb.makeCall(SqlStdOperatorTable.IS_NOT_NULL, c1Field));

        RexNode overC1 = rb.makeOver(aggType, SqlStdOperatorTable.SUM,
                com.google.common.collect.ImmutableList.of(b.field(qtyIdx)),
                com.google.common.collect.ImmutableList.of(b.field(c1Idx)),
                com.google.common.collect.ImmutableList.<org.apache.calcite.rex.RexFieldCollation>of(),
                b.unboundedPreceding(), b.unboundedFollowing(), true, false, false, false);
        RexNode overC = rb.makeOver(aggType, SqlStdOperatorTable.SUM,
                com.google.common.collect.ImmutableList.of(b.field(qtyIdx)),
                com.google.common.collect.ImmutableList.of(b.field(c1Idx), b.field(c2Idx)),
                com.google.common.collect.ImmutableList.<org.apache.calcite.rex.RexFieldCollation>of(),
                b.unboundedPreceding(), b.unboundedFollowing(), true, false, false, false);

        b.project(b.field(c1Idx), b.field(c2Idx), overC1, overC);
        b.aggregate(b.groupKey(0, 1, 2, 3));          // DISTINCT

        List<RexNode> out = new ArrayList<>();
        for (int i = 0; i < project.getRowType().getFieldCount(); i++) {
            RelDataType target = project.getRowType().getFieldList().get(i).getType();
            out.add(rb.makeCast(target, b.field(i)));
        }
        b.project(out);

        RelNode rhs = b.build();
        if (dbg) {
            System.err.println("[WindowRule] lhsType=" + project.getRowType()
                    + "\n" + RelOptUtil.toString(rhs)
                    + "[WindowRule] rhsType=" + rhs.getRowType());
        }
        try {
            call.transformTo(rhs);
            if (dbg) {
                System.err.println("[WindowRule] transformTo done");
            }
        } catch (RuntimeException ex) {
            if (dbg) {
                System.err.println("[WindowRule] transformTo FAILED: " + ex);
            }
        }
    }
}
