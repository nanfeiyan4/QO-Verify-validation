package demo.qoverify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.volcano.RelSubset;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;
import org.apache.calcite.tools.Frameworks;

/**
 * 把 §3.2 / §3.3 的变换规则实现成真正的 Calcite RelOptRule，并验证：
 *   规则不在 -> QO-Verify 返回 Unknown；规则在 -> True。
 */
public class RuleDemo {

    static final org.apache.calcite.jdbc.JavaTypeFactoryImpl TF =
            new org.apache.calcite.jdbc.JavaTypeFactoryImpl(
                    org.apache.calcite.rel.type.RelDataTypeSystem.DEFAULT);
    static SchemaPlus root;

    // ------------------------------------------------------------ schema
    static void addTable(String name, String[] cols) {
        root.add(name, new AbstractTable() {
            @Override
            public RelDataType getRowType(RelDataTypeFactory tf) {
                RelDataTypeFactory.Builder b = tf.builder();
                for (String c : cols) {
                    boolean isInt = c.endsWith("_i");
                    b.add(c, isInt
                            ? tf.createSqlType(org.apache.calcite.sql.type.SqlTypeName.INTEGER)
                            : tf.createSqlType(org.apache.calcite.sql.type.SqlTypeName.VARCHAR, 20));
                }
                return b.build();
            }
        });
    }

    // ------------------------------------------------------------ 构造 / 解析
    interface RelMaker {
        RelNode make(RelOptCluster cluster, CalciteCatalogReader cat) throws Exception;
    }

    static CalciteCatalogReader catalogReader() {
        return new CalciteCatalogReader(CalciteSchema.from(root),
                Collections.singletonList(""), TF,
                new CalciteConnectionConfigImpl(new Properties()));
    }

    static RelNode parse(String sql, RelOptCluster cluster) throws Exception {
        CalciteCatalogReader cat = catalogReader();
        SqlValidator validator = SqlValidatorUtil.newValidator(
                SqlStdOperatorTable.instance(), cat, TF,
                SqlValidator.Config.DEFAULT.withIdentifierExpansion(true));
        SqlNode node = SqlParser.create(sql, SqlParser.config()
                .withCaseSensitive(false)
                .withUnquotedCasing(org.apache.calcite.avatica.util.Casing.TO_LOWER)
                .withQuotedCasing(org.apache.calcite.avatica.util.Casing.UNCHANGED))
                .parseQuery();
        SqlToRelConverter conv = new SqlToRelConverter(
                (org.apache.calcite.plan.RelOptTable.ViewExpander) (rt, qs, sp, vp) -> null,
                validator, cat, cluster, StandardConvertletTable.INSTANCE,
                SqlToRelConverter.config().withTrimUnusedFields(false).withExpand(false));
        RelRoot r = conv.convertQuery(validator.validate(node), false, true);
        return r.rel;
    }

    // ------------------------------------------------------------ memo
    static class Memo {
        RelSubset root;
        long millis;
        int groups;
    }

    static Memo buildMemo(RelMaker maker, List<RelOptRule> rules) throws Exception {
        VolcanoPlanner planner = new VolcanoPlanner() {
            @Override
            public void prune(RelNode rel) {
                // 关闭 cost-based pruning：保留全部备选
            }
        };
        planner.addRelTraitDef(ConventionTraitDef.INSTANCE);
        RelOptCluster cluster = RelOptCluster.create(planner, new RexBuilder(TF));
        CalciteCatalogReader cat = catalogReader();
        for (RelOptRule r0 : rules) {
            planner.addRule(r0);
        }
        RelNode rel = maker.make(cluster, cat);
        long t0 = System.nanoTime();
        planner.setRoot(rel);
        try {
            planner.findBestExp();
        } catch (RuntimeException ignore) {
            // 只关心 memo 内容
        }
        Memo m = new Memo();
        m.millis = (System.nanoTime() - t0) / 1_000_000L;
        RelNode r = planner.getRoot();
        m.root = (r instanceof RelSubset) ? (RelSubset) r : null;
        return m;
    }

    static List<RelOptRule> baseRules() {
        return baseRules(true);
    }

    static List<RelOptRule> baseRules(boolean withFilterIntoJoin) {
        List<RelOptRule> r = new ArrayList<>();
        r.add(CoreRules.JOIN_COMMUTE);
        r.add(CoreRules.JOIN_ASSOCIATE);
        r.add(CoreRules.FILTER_MERGE);
        r.add(CoreRules.PROJECT_MERGE);
        if (withFilterIntoJoin) {
            r.add(CoreRules.FILTER_INTO_JOIN);
        }
        r.add(CoreRules.PROJECT_REMOVE);
        return r;
    }

    // ------------------------------------------------------------ 场景
    static final String Q4_SQL = ""
            + "SELECT t1.cust, t1.tot AS s2000, t2.tot AS s2001 "
            + "FROM (SELECT cust, yr_i, SUM(amt_i) AS tot FROM sales GROUP BY cust, yr_i) t1, "
            + "     (SELECT cust, yr_i, SUM(amt_i) AS tot FROM sales GROUP BY cust, yr_i) t2 "
            + "WHERE t1.cust = t2.cust AND t1.yr_i = 2000 AND t2.yr_i = 2001";

    static final String Q5_SQL = ""
            + "SELECT cust, "
            + "MAX(CASE WHEN yr_i = 2000 THEN tot END) AS s2000, "
            + "MAX(CASE WHEN yr_i = 2001 THEN tot END) AS s2001 "
            + "FROM (SELECT cust, yr_i, SUM(amt_i) AS tot FROM sales GROUP BY cust, yr_i) "
            + "WHERE cust IS NOT NULL GROUP BY cust "
            + "HAVING SUM(CASE WHEN yr_i = 2000 THEN 1 ELSE 0 END) > 0 "
            + "AND SUM(CASE WHEN yr_i = 2001 THEN 1 ELSE 0 END) > 0";

    /** §3.3 的 LHS：两个标量子查询的 CROSS JOIN，Calcite 会转成 Join(inner,true) of 两个 Aggregate */
    static final String Q6_SQL = ""
            + "SELECT a.promo_sales, b.total FROM "
            + "(SELECT SUM(price_i) AS promo_sales FROM s JOIN promo "
            + "   ON s.promo_i = promo.pk_i WHERE promo.ch = 'Y') a, "
            + "(SELECT SUM(price_i) AS total FROM s) b";

    static final String Q7_SQL = ""
            + "SELECT SUM(CASE WHEN p.pk_i IS NOT NULL THEN s.price_i END) AS promo_sales, "
            + "       SUM(s.price_i) AS total "
            + "FROM s LEFT JOIN promo p ON s.promo_i = p.pk_i AND p.ch = 'Y'";

    static String run(String title, RelMaker lhs, RelMaker rhs, RelOptRule newRule,
                      List<RelOptRule> base) throws Exception {
        List<RelOptRule> without = new ArrayList<>(base);
        List<RelOptRule> with = new ArrayList<>(base);
        with.add(newRule);

        Memo l0 = buildMemo(lhs, without);
        Memo r0 = buildMemo(rhs, without);
        boolean v0 = QoVerifyDemo.matchGroups(l0.root, r0.root, new QoVerifyDemo.Ctx());

        Memo l1 = buildMemo(lhs, with);
        Memo r1 = buildMemo(rhs, with);
        boolean v1 = QoVerifyDemo.matchGroups(l1.root, r1.root, new QoVerifyDemo.Ctx());

        String s0 = v0 ? "True" : "Unknown";
        String s1 = v1 ? "True" : "Unknown";
        System.out.printf("  %-46s 规则不在: %-8s  规则在: %-8s%n", title, s0, s1);
        return s1;
    }

    public static void main(String[] args) throws Exception {
        root = Frameworks.createRootSchema(false);
        addTable("sales", new String[]{"cust", "yr_i", "amt_i"});
        addTable("s", new String[]{"promo_i", "price_i"});
        addTable("promo", new String[]{"pk_i", "ch"});

        System.out.println();
        System.out.println("§3.2 / §3.3 的 RelOptRule 实现验证");
        System.out.println("================================================================");

        RelMaker q4 = (c, cat) -> parse(Q4_SQL, c);
        RelMaker q5 = (c, cat) -> parse(Q5_SQL, c);
        RelMaker q6 = (c, cat) -> parse(Q6_SQL, c);
        RelMaker q7 = (c, cat) -> parse(Q7_SQL, c);

        // §3.2 需要 Filter 仍然在 join 之上，因此这一组不注册 FILTER_INTO_JOIN
        //（否则它会把 filter 折进 join 条件，把要匹配的形状破坏掉）
        run("§3.2 Query4(self-join) vs Query5(条件聚合)", q4, q5, PivotRule.INSTANCE,
                baseRules(false));
        run("§3.3 Query6(两个标量子查询) vs Query7(LOJ)", q6, q7, ScalarAggLojRule.INSTANCE,
                baseRules(true));

        System.out.println("================================================================");
        System.out.println("说明：'规则不在' 时 Unknown 是因为证明链断在缺失的变换规则上；");
        System.out.println("      把规则加进 VolcanoPlanner 后，memo 里就出现了与另一侧同构的表达式，");
        System.out.println("      于是 QO-Verify（算法一行未改）返回 True。");
    }
}
