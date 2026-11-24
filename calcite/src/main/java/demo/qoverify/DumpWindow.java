package demo.qoverify;

import java.util.Collections;
import java.util.Properties;

import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
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

/** 打印 §3.1 两条 SQL 的逻辑计划，用于确定 Window 规则的匹配与产出形状。 */
public class DumpWindow {

    static final JavaTypeFactoryImpl TF =
            new JavaTypeFactoryImpl(RelDataTypeSystem.DEFAULT);

    static void addTable(SchemaPlus root, String name, String[] cols) {
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

    public static void main(String[] args) throws Exception {
        SchemaPlus root = Frameworks.createRootSchema(false);
        addTable(root, "lineitem", new String[]{"l_partkey_i", "l_suppkey_i", "l_quantity_i"});

        String[] sqls = {
            // Q2（LHS）：两个分组子查询的 join
            "SELECT d2.p2, d2.s2, d1.q1, d2.q2 FROM "
          + "(SELECT l_partkey_i AS p1, SUM(l_quantity_i) AS q1 FROM lineitem GROUP BY l_partkey_i) d1, "
          + "(SELECT l_partkey_i AS p2, l_suppkey_i AS s2, SUM(l_quantity_i) AS q2 "
          + " FROM lineitem GROUP BY l_partkey_i, l_suppkey_i) d2 "
          + "WHERE d1.p1 = d2.p2",
            // Q3（RHS）：窗口函数 + DISTINCT
            "SELECT DISTINCT l_partkey_i AS p2, l_suppkey_i AS s2, "
          + "SUM(l_quantity_i) OVER (PARTITION BY l_partkey_i) AS q1, "
          + "SUM(l_quantity_i) OVER (PARTITION BY l_partkey_i, l_suppkey_i) AS q2 "
          + "FROM lineitem",
        };

        for (int i = 0; i < sqls.length; i++) {
            System.out.println("\n============ SQL #" + (i + 1) + " ============");
            System.out.println(sqls[i]);
            System.out.println("------------- plan -------------");
            try {
                System.out.println(RelOptUtil.toString(convert(root, sqls[i])));
            } catch (Exception e) {
                System.out.println("ERROR: " + e.getClass().getSimpleName() + ": "
                        + String.valueOf(e.getMessage()).split("\n")[0]);
            }
        }
    }

    static org.apache.calcite.rel.RelNode convert(SchemaPlus root, String sql) throws Exception {
        VolcanoPlanner planner = new VolcanoPlanner();
        planner.addRelTraitDef(org.apache.calcite.plan.ConventionTraitDef.INSTANCE);
        RelOptCluster cluster = RelOptCluster.create(planner, new RexBuilder(TF));

        CalciteCatalogReader cat = new CalciteCatalogReader(
                CalciteSchema.from(root), Collections.singletonList(""), TF,
                new CalciteConnectionConfigImpl(new Properties()));
        SqlValidator validator = SqlValidatorUtil.newValidator(
                SqlStdOperatorTable.instance(), cat, TF,
                SqlValidator.Config.DEFAULT.withIdentifierExpansion(true));

        SqlNode node = SqlParser.create(sql, SqlParser.config()
                .withCaseSensitive(false)
                .withUnquotedCasing(org.apache.calcite.avatica.util.Casing.TO_LOWER)
                .withQuotedCasing(org.apache.calcite.avatica.util.Casing.UNCHANGED))
                .parseQuery();
        SqlNode validated = validator.validate(node);

        SqlToRelConverter conv = new SqlToRelConverter(
                (org.apache.calcite.plan.RelOptTable.ViewExpander) (rt, qs, sp, vp) -> null,
                validator, cat, cluster, StandardConvertletTable.INSTANCE,
                SqlToRelConverter.config().withTrimUnusedFields(false).withExpand(false));
        RelRoot r = conv.convertQuery(validated, false, true);
        return r.rel;
    }
}
