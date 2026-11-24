package demo.qoverify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.volcano.RelSubset;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;
import org.apache.calcite.tools.Frameworks;

/**
 * QO-Verify 机制验证 (2/2)：真实引擎（Apache Calcite）
 *
 * 做法：
 *   1. 用 Calcite 的 VolcanoPlanner 各为两条 SQL 生成 memo（关掉剪枝）
 *   2. 实现论文 Algorithm 1：MatchGroups / MatchExprs
 *   3. 用"规则集开关"做消融：去掉 JOIN_COMMUTE 后，join 换序的 pair 应从 True 变 Unknown
 */
public class QoVerifyDemo {

    static final JavaTypeFactoryImpl TYPE_FACTORY =
            new JavaTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
    static SchemaPlus rootSchema;
    static boolean WITH_COMMUTE = true;
    static boolean WITH_FILTER_INTO_JOIN = true;
    static boolean WITH_PROJECT_REMOVE = true;
    static boolean WITH_JOIN_RULES = true;

    // ---------------------------------------------------------------- schema
    static void addTable(String name, String[] cols, RelDataTypeFactory typeFactory) {
        SchemaPlus s = rootSchema;
        s.add(name, new AbstractTable() {
            @Override
            public RelDataType getRowType(RelDataTypeFactory tf) {
                RelDataTypeFactory.Builder b = tf.builder();
                for (String c : cols) {
                    b.add(c, c.startsWith("k") || c.startsWith("x") || c.startsWith("y")
                            || c.startsWith("p") || c.startsWith("q") || c.startsWith("v")
                            || c.startsWith("a_") || c.endsWith("_i")
                            ? tf.createSqlType(org.apache.calcite.sql.type.SqlTypeName.INTEGER)
                            : tf.createSqlType(org.apache.calcite.sql.type.SqlTypeName.VARCHAR, 20));
                }
                return b.build();
            }
        });
    }

    // ---------------------------------------------------------------- SQL -> Rel
    static RelRoot convert(SqlValidator validator, RelOptCluster cluster, String sql) throws Exception {
        SqlParser parser = SqlParser.create(sql, SqlParser.config()
                .withCaseSensitive(false)
                .withUnquotedCasing(org.apache.calcite.avatica.util.Casing.TO_LOWER)
                .withQuotedCasing(org.apache.calcite.avatica.util.Casing.UNCHANGED));
        SqlNode node = parser.parseQuery();
        SqlNode validated = validator.validate(node);
        SqlToRelConverter converter = new SqlToRelConverter(
                (RelOptTable.ViewExpander) (rowType, queryString, schemaPath, viewPath) -> null,
                validator,
                new CalciteCatalogReader(CalciteSchema.from(rootSchema),
                        Collections.singletonList(""), TYPE_FACTORY,
                        new CalciteConnectionConfigImpl(new Properties())),
                cluster,
                StandardConvertletTable.INSTANCE,
                SqlToRelConverter.config()
                        .withTrimUnusedFields(false)
                        .withExpand(true));
        return converter.convertQuery(validated, false, true);
    }

    static SqlValidator newValidator() {
        CalciteCatalogReader catalogReader = new CalciteCatalogReader(
                CalciteSchema.from(rootSchema),
                Collections.singletonList(""),
                TYPE_FACTORY,
                new CalciteConnectionConfigImpl(new Properties()));
        return SqlValidatorUtil.newValidator(SqlStdOperatorTable.instance(), catalogReader,
                TYPE_FACTORY, SqlValidator.Config.DEFAULT.withIdentifierExpansion(true));
    }

    // ---------------------------------------------------------------- memo
    /** 一条查询的 memo：用 VolcanoPlanner 搜索，关闭剪枝，返回根 subset */
    static RelSubset buildMemo(String sql, List<RelOptRule> rules, Stats st) throws Exception {
        VolcanoPlanner planner = new VolcanoPlanner() {
            @Override
            public void prune(RelNode rel) {
                // 关闭 cost-based pruning：保留全部备选表达式
                // （对应论文 "turn off cost-based pruning"，保证 memo 里能看到所有备选）
            }
        };
        planner.addRelTraitDef(org.apache.calcite.plan.ConventionTraitDef.INSTANCE);
        RelOptCluster cluster = RelOptCluster.create(planner, new RexBuilder(TYPE_FACTORY));
        RelRoot root = convert(newValidator(), cluster, sql);
        long t0 = System.nanoTime();
        for (RelOptRule r : rules) {
            planner.addRule(r);           // 必须在 setRoot 之前注册，否则根节点不会触发规则
        }
        planner.setRoot(root.rel);
        try {
            planner.findBestExp();
        } catch (RuntimeException ex) {
            // 我们只需要 memo 内容，不需要最终选中计划；
            // 缺少统计/代价模型时 VolcanoPlanner 会在收尾选计划阶段抛 CannotPlanException，
            // 此时规则已全部触发、memo 已完整，忽略即可。
        }
        st.memoMillis += (System.nanoTime() - t0) / 1_000_000L;
        RelNode r = planner.getRoot();
        if (!(r instanceof RelSubset)) {
            return null;
        }
        return (RelSubset) r;
    }

    /** 统计 memo 规模（沿 subset 图遍历） */
    static void countMemo(RelSubset root, Map<RelSubset, Boolean> seen) {
        if (root == null || seen.containsKey(root)) {
            return;
        }
        seen.put(root, Boolean.TRUE);
        for (RelNode e : root.getRelList()) {
            for (RelNode in : e.getInputs()) {
                if (in instanceof RelSubset) {
                    countMemo((RelSubset) in, seen);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 算子签名（operator specific）
    /** 把 RexInputRef 解析成 "表名.列名" 的绝对列名，从而对 join/谓词顺序免疫 */
    static String absCol(RelNode rel, int field) {
        if (rel instanceof TableScan) {
            List<String> qn = ((TableScan) rel).getTable().getQualifiedName();
            return qn.get(qn.size() - 1) + "." + rel.getRowType().getFieldNames().get(field);
        }
        if (rel instanceof Project) {
            Project p = (Project) rel;
            RexNode e = p.getProjects().get(field);
            if (e instanceof RexInputRef) {
                return absCol(p.getInput(), ((RexInputRef) e).getIndex());
            }
            return "EXPR";
        }
        if (rel instanceof Filter) {
            return absCol(((Filter) rel).getInput(), field);
        }
        if (rel instanceof Join) {
            Join j = (Join) rel;
            int n = j.getLeft().getRowType().getFieldCount();
            return field < n ? absCol(j.getLeft(), field) : absCol(j.getRight(), field - n);
        }
        if (rel instanceof Aggregate) {
            Aggregate a = (Aggregate) rel;
            int n = a.getGroupCount();
            if (field < n) {
                return "G" + a.getGroupSet().nth(field);
            }
            return "AGG" + (field - n);
        }
        return "F" + field;
    }

    /** 渲染谓词：等值对的操作数排序；顶层 AND 的项排序 */
    static String render(RexNode e, RelNode ctx) {
        if (e instanceof RexInputRef) {
            return absCol(ctx, ((RexInputRef) e).getIndex());
        }
        if (e instanceof RexCall) {
            RexCall c = (RexCall) e;
            List<String> args = c.getOperands().stream()
                    .map(o -> render(o, ctx)).collect(Collectors.toList());
            if (c.getKind() == SqlKind.EQUALS && args.size() == 2) {
                List<String> s = new ArrayList<>(args);
                Collections.sort(s);
                return s.get(0) + "=" + s.get(1);
            }
            return c.getKind() + "(" + String.join(",", args) + ")";
        }
        return e.toString();
    }

    static String renderPred(RexNode cond, RelNode ctx) {
        List<RexNode> conj = RelOptUtil.conjunctions(cond);
        List<String> parts = conj.stream().map(n -> render(n, ctx)).collect(Collectors.toList());
        Collections.sort(parts);
        return String.join(" & ", parts);
    }

    static String opSig(RelNode n) {
        if (n instanceof TableScan) {
            List<String> qn = ((TableScan) n).getTable().getQualifiedName();
            return "GET:" + qn.get(qn.size() - 1);
        }
        if (n instanceof Join) {
            Join j = (Join) n;
            return "JOIN:" + j.getJoinType() + ":" + renderPred(j.getCondition(), j);
        }
        if (n instanceof Filter) {
            return "FILTER:" + renderPred(((Filter) n).getCondition(), ((Filter) n).getInput());
        }
        if (n instanceof Aggregate) {
            Aggregate a = (Aggregate) n;
            List<String> gs = new ArrayList<>();
            for (int i = 0; i < a.getGroupCount(); i++) {
                gs.add(absCol(a.getInput(), a.getGroupSet().nth(i)));
            }
            return "AGG:groups=" + gs + ":calls=" + a.getAggCallList();
        }
        if (n instanceof Project) {
            Project p = (Project) n;
            List<String> xs = new ArrayList<>();
            for (RexNode e : p.getProjects()) {
                xs.add(render(e, p.getInput()));
            }
            return "PROJ:" + xs;
        }
        if (n instanceof Union) {
            return "UNION:all=" + ((Union) n).all;
        }
        return n.getClass().getSimpleName();
    }

    // ---------------------------------------------------------------- 归一化（论文 §2.2 的 "broaden normalization"）
    /**
     * 剥离"纯列置换"的 Project：它的项目全是 RexInputRef，只重排列序、不改变行多重集。
     * Calcite 的 JOIN_COMMUTE / JOIN_ASSOCIATE 会在改写后的 join 上包一层这样的 Project
     * 来恢复列序，导致两个 memo 里永远没有"结构完全相同"的表达式。
     * 按论文的思路，比较前先把这层包装归一化掉。
     */
    static RelNode stripPerm(RelNode r) {
        while (r instanceof Project) {
            Project p = (Project) r;
            boolean pure = true;
            for (RexNode e : p.getProjects()) {
                if (!(e instanceof RexInputRef)) {
                    pure = false;
                    break;
                }
            }
            if (!pure) {
                break;
            }
            r = p.getInput();
        }
        return r;
    }

    /** 输出列的绝对名字（按顺序）。用于保证"最终列序"仍然一致，避免把 SELECT a,b 与 SELECT b,a 判成等价。 */
    static List<String> outputCols(RelNode root) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < root.getRowType().getFieldCount(); i++) {
            out.add(absCol(root, i));
        }
        return out;
    }

    // ---------------------------------------------------------------- Algorithm 1
    static class Ctx {
        long gCall, gHit, eCall, eHit, steps;
        final Map<String, Boolean> gCache = new LinkedHashMap<>();
        final Map<String, Boolean> eCache = new LinkedHashMap<>();
        final Map<String, Boolean> inProgress = new LinkedHashMap<>();
    }

    static String gkey(RelSubset a, RelSubset b) {
        return System.identityHashCode(a) + "|" + System.identityHashCode(b);
    }

    static String ekey(RelNode a, RelNode b) {
        return System.identityHashCode(a) + "|" + System.identityHashCode(b);
    }

    static boolean matchGroups(RelSubset g1, RelSubset g2, Ctx ctx) {
        String k = gkey(g1, g2);
        Boolean cached = ctx.gCache.get(k);
        if (cached != null) {
            ctx.gHit++;
            return cached;
        }
        if (ctx.inProgress.containsKey(k)) {
            return false;
        }
        if (++ctx.steps > 5_000_000L) {
            throw new IllegalStateException("step budget exceeded");
        }
        ctx.inProgress.put(k, Boolean.TRUE);
        ctx.gCall++;
        boolean res = false;
        for (RelNode e1 : g1.getRelList()) {
            for (RelNode e2 : g2.getRelList()) {
                if (matchExprs(e1, e2, ctx)) {
                    res = true;
                    break;
                }
            }
            if (res) {
                break;
            }
        }
        ctx.inProgress.remove(k);
        ctx.gCache.put(k, res);
        return res;
    }

    static boolean matchExprs(RelNode e1, RelNode e2, Ctx ctx) {
        ctx.steps++;
        String k = ekey(e1, e2);
        Boolean cached = ctx.eCache.get(k);
        if (cached != null) {
            ctx.eHit++;
            return cached;
        }
        if (ctx.inProgress.containsKey(k)) {
            return false;
        }
        if (ctx.steps > 5_000_000L) {
            throw new IllegalStateException("step budget exceeded");
        }
        ctx.inProgress.put(k, Boolean.TRUE);
        ctx.eCall++;
        // 归一化：先剥掉纯列置换的 Project，再比较核心
        boolean ok = matchNode(stripPerm(e1), stripPerm(e2), ctx, 0);
        ctx.inProgress.remove(k);
        ctx.eCache.put(k, ok);
        return ok;
    }

    /** 核心比较：节点 vs 节点 / 组 vs 组 / 节点 vs 组 */
    static boolean matchNode(RelNode a, RelNode b, Ctx ctx, int depth) {
        if (depth > 120) {
            return false;                       // 深度上限，兜住退化的自引用
        }
        if (a instanceof RelSubset && b instanceof RelSubset) {
            return matchGroups((RelSubset) a, (RelSubset) b, ctx);
        }
        if (a instanceof RelSubset) {
            String k = "NA|" + ekey(a, b);
            if (ctx.inProgress.containsKey(k)) {
                return false;                   // 环：Project 的输入可能就是它所在的那个 subset
            }
            ctx.inProgress.put(k, Boolean.TRUE);
            try {
                for (RelNode m : ((RelSubset) a).getRelList()) {
                    if (matchNode(stripPerm(m), b, ctx, depth + 1)) {
                        return true;
                    }
                }
                return false;
            } finally {
                ctx.inProgress.remove(k);
            }
        }
        if (b instanceof RelSubset) {
            String k = "NB|" + ekey(a, b);
            if (ctx.inProgress.containsKey(k)) {
                return false;
            }
            ctx.inProgress.put(k, Boolean.TRUE);
            try {
                for (RelNode m : ((RelSubset) b).getRelList()) {
                    if (matchNode(a, stripPerm(m), ctx, depth + 1)) {
                        return true;
                    }
                }
                return false;
            } finally {
                ctx.inProgress.remove(k);
            }
        }
        if (!opSig(a).equals(opSig(b))) {
            return false;
        }
        List<RelNode> ia = a.getInputs();
        List<RelNode> ib = b.getInputs();
        if (ia.size() != ib.size()) {
            return false;
        }
        if (ia.isEmpty()) {
            return true;
        }
        // 内连接：子节点顺序可交换（交换律）-> 按"无序对"比较；外连接必须保序
        if (a instanceof Join && b instanceof Join
                && ((Join) a).getJoinType() == org.apache.calcite.rel.core.JoinRelType.INNER
                && ((Join) b).getJoinType() == org.apache.calcite.rel.core.JoinRelType.INNER) {
            boolean direct = matchNode(ia.get(0), ib.get(0), ctx, depth + 1)
                    && matchNode(ia.get(1), ib.get(1), ctx, depth + 1);
            if (direct) {
                return true;
            }
            return matchNode(ia.get(0), ib.get(1), ctx, depth + 1)
                    && matchNode(ia.get(1), ib.get(0), ctx, depth + 1);
        }
        for (int i = 0; i < ia.size(); i++) {
            if (!matchNode(ia.get(i), ib.get(i), ctx, depth + 1)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 笨办法（只比初始表达式）
    static boolean naiveEqual(RelNode a, RelNode b) {
        if (!opSig(a).equals(opSig(b))) {
            return false;
        }
        if (a.getInputs().size() != b.getInputs().size()) {
            return false;
        }
        for (int i = 0; i < a.getInputs().size(); i++) {
            if (!naiveEqual(a.getInput(i), b.getInput(i))) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 规则集
    static List<RelOptRule> rules() {
        List<RelOptRule> r = new ArrayList<>();
        if (WITH_JOIN_RULES && WITH_COMMUTE) {
            r.add(CoreRules.JOIN_COMMUTE);
        }
        if (WITH_JOIN_RULES) {
            r.add(CoreRules.JOIN_ASSOCIATE);
        }
        r.add(CoreRules.FILTER_MERGE);
        r.add(CoreRules.PROJECT_MERGE);
        if (WITH_FILTER_INTO_JOIN) {
            r.add(CoreRules.FILTER_INTO_JOIN);
        }
        if (WITH_PROJECT_REMOVE) {
            r.add(CoreRules.PROJECT_REMOVE);
        }
        return r;
    }

    // ---------------------------------------------------------------- 场景
    static class Stats {
        long memoMillis;
        long groups;
    }

    static class Row {
        String name, sql1, sql2, naive, qov;
        boolean sameOutCols = true;
        long memoMs, matchMs, groups, exprs;
    }

    static Row verify(String name, String sql1, String sql2) throws Exception {
        Row row = new Row();
        row.name = name;
        row.sql1 = sql1;
        row.sql2 = sql2;

        // 笨办法：只比较转换后的初始表达式（搜索之前）
        {
            VolcanoPlanner p1 = new VolcanoPlanner();
            RelOptCluster c1 = RelOptCluster.create(p1, new RexBuilder(TYPE_FACTORY));
            RelRoot r1 = convert(newValidator(), c1, sql1);
            VolcanoPlanner p2 = new VolcanoPlanner();
            RelOptCluster c2 = RelOptCluster.create(p2, new RexBuilder(TYPE_FACTORY));
            RelRoot r2 = convert(newValidator(), c2, sql2);
            row.naive = naiveEqual(r1.rel, r2.rel) ? "True" : "Unknown";
            // 归一化只放宽"内部列序"，最终输出列序必须一致，否则连"同一结果集"都不是
            row.sameOutCols = outputCols(r1.rel).equals(outputCols(r2.rel));
        }

        Stats s1 = new Stats(), s2 = new Stats();
        RelSubset m1 = buildMemo(sql1, rules(), s1);
        RelSubset m2 = buildMemo(sql2, rules(), s2);
        if (m1 == null || m2 == null) {
            row.qov = "ERR";
            return row;
        }
        if (!row.sameOutCols) {
            row.qov = "Unknown";        // 输出列序不同 -> 不可能是同一查询结果
            return row;
        }
        Ctx ctx = new Ctx();
        long t0 = System.nanoTime();
        boolean ok = matchGroups(m1, m2, ctx);
        row.matchMs = (System.nanoTime() - t0) / 1_000_000L;
        row.qov = ok ? "True" : "Unknown";
        row.memoMs = s1.memoMillis + s2.memoMillis;
        Map<RelSubset, Boolean> seen = new IdentityHashMap<>();
        countMemo(m1, seen);
        row.groups = seen.size();
        long ex = 0;
        for (RelSubset ss : seen.keySet()) {
            ex += ss.getRelList().size();
        }
        row.exprs = ex;
        return row;
    }

    public static void main(String[] args) throws Exception {
        WITH_COMMUTE = !Arrays.asList(args).contains("--nocommute");
        WITH_FILTER_INTO_JOIN = !Arrays.asList(args).contains("--nofilter");
        WITH_PROJECT_REMOVE = !Arrays.asList(args).contains("--noremove");
        WITH_JOIN_RULES = !Arrays.asList(args).contains("--nojoinrules");
        rootSchema = Frameworks.createRootSchema(false);
        addTable("a", new String[]{"x_i", "a"}, TYPE_FACTORY);
        addTable("b", new String[]{"y_i", "b"}, TYPE_FACTORY);
        addTable("c", new String[]{"p_i", "q_i"}, TYPE_FACTORY);
        addTable("t", new String[]{"k_i", "v_i"}, TYPE_FACTORY);

        List<Row> rows = new ArrayList<>();
        // 注意：一律用显式列清单，避免 "SELECT *" 因 FROM 换序导致输出列序不同（那本身就不是等价查询）
        rows.add(verify("S1 同一查询的两种写法",
                "SELECT a.x_i, b.y_i FROM a JOIN b ON a.x_i = b.y_i",
                "SELECT a.x_i, b.y_i FROM a, b WHERE a.x_i = b.y_i"));
        rows.add(verify("S2 join 换序（交换律）",
                "SELECT a.x_i, b.y_i FROM a, b WHERE a.x_i = b.y_i",
                "SELECT a.x_i, b.y_i FROM b, a WHERE a.x_i = b.y_i"));
        rows.add(verify("S3 三表换序（交换+结合）",
                "SELECT a.x_i, b.y_i, c.p_i FROM a, b, c WHERE a.x_i = b.y_i AND b.y_i = c.p_i",
                "SELECT a.x_i, b.y_i, c.p_i FROM c, b, a WHERE a.x_i = b.y_i AND b.y_i = c.p_i"));
        rows.add(verify("S4 合取项顺序不同",
                "SELECT a.x_i, b.y_i FROM a, b WHERE a.x_i = b.y_i AND a.a = 'k'",
                "SELECT a.x_i, b.y_i FROM a, b WHERE a.a = 'k' AND a.x_i = b.y_i"));
        rows.add(verify("S5 非等价（谓词常量不同）",
                "SELECT a.x_i, b.y_i FROM a, b WHERE a.x_i = b.y_i AND a.a = 'k'",
                "SELECT a.x_i, b.y_i FROM a, b WHERE a.x_i = b.y_i AND a.a = 'j'"));
        rows.add(verify("S6 非等价（换了连接列）",
                "SELECT a.x_i, b.y_i, c.p_i FROM a, b, c WHERE a.x_i = b.y_i AND b.y_i = c.p_i",
                "SELECT a.x_i, b.y_i, c.p_i FROM a, b, c WHERE a.x_i = b.y_i AND b.y_i = c.q_i"));
        rows.add(verify("S8 谓词下推（Filter 在 join 上 vs 在输入侧）",
                "SELECT a.x_i, b.y_i FROM a, b WHERE a.x_i = b.y_i AND a.a = 'k'",
                "SELECT X.x_i, b.y_i FROM (SELECT * FROM a WHERE a.a = 'k') X, b WHERE X.x_i = b.y_i"));
        rows.add(verify("S7 聚合 + 子查询投影",
                "SELECT k_i, SUM(v_i) FROM t GROUP BY k_i",
                "SELECT k_i, SUM(v_i) FROM (SELECT k_i, v_i FROM t) GROUP BY k_i"));
        rows.add(verify("S10 输出列序不同（归一化的反向对照）",
                "SELECT a.x_i, a.a FROM a, b WHERE a.x_i = b.y_i",
                "SELECT a.a, a.x_i FROM a, b WHERE a.x_i = b.y_i"));
        rows.add(verify("S9 非等价（CASE 的 ELSE 0 vs ELSE NULL）",
                "SELECT SUM(CASE WHEN b.b IS NOT NULL THEN a.x_i END) FROM a LEFT JOIN b ON a.x_i = b.y_i",
                "SELECT SUM(CASE WHEN b.b IS NOT NULL THEN a.x_i ELSE 0 END) FROM a LEFT JOIN b ON a.x_i = b.y_i"));
        System.out.println();
        System.out.println("规则集: " + (WITH_COMMUTE ? "含 JOIN_COMMUTE" : "**不含** JOIN_COMMUTE") + ", " + (WITH_FILTER_INTO_JOIN ? "含 FILTER_INTO_JOIN" : "**不含** FILTER_INTO_JOIN") + ", " + (WITH_PROJECT_REMOVE ? "含 PROJECT_REMOVE" : "**不含** PROJECT_REMOVE") + ", " + (WITH_JOIN_RULES ? "含 JOIN 交换/结合" : "**不含** JOIN 交换/结合"));
        System.out.println();
        System.out.printf("%-34s %-10s %-10s %6s %6s %9s %9s%n",
                "场景", "笨办法", "QO-Verify", "group", "expr", "memo(ms)", "match(ms)");
        System.out.println("--------------------------------------------------------------------------------------------");
        int trueCnt = 0, falsePos = 0;
        for (Row r : rows) {
            System.out.printf("%-34s %-10s %-10s %6d %6d %9d %9d%n",
                    r.name, r.naive, r.qov, r.groups, r.exprs, r.memoMs, r.matchMs);
            if ("True".equals(r.qov)) {
                trueCnt++;
            }
            if ("True".equals(r.qov) && r.name.contains("非等价")) {
                falsePos++;
            }
        }
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("True 命中 %d / %d 对；误报（非等价却被判 True）= %d%n", trueCnt, rows.size(), falsePos);
        System.out.println("（memo 生成时间与 match 时间的对比，即论文“成本大头在 memo 生成”的结构性复现）");

        // 导出 QO-Verify 判定，供 fuzz 脚本对照
        try (java.io.PrintWriter pw = new java.io.PrintWriter(
                new java.io.FileWriter(System.getProperty("qov.csv", "/root/qoverify/results.csv")))) {
            for (Row r : rows) {
                pw.println(r.name + "|" + r.qov + "|" + r.naive);
            }
        }
        System.out.println("已导出判定到 /root/qoverify/results.csv");
    }
}
