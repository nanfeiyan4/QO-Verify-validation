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

/** 打印某条 SQL 在 Calcite 里的初始逻辑计划，用于确定 RelOptRule 的匹配形状。 */
public class DumpPlan {

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
        addTable(root, "sales", new String[]{"cust", "yr_i", "amt_i"});
        addTable(root, "s", new String[]{"promo_i", "price_i"});
        addTable(root, "promo", new String[]{"pk_i", "ch"});

        String[] sqls = {
            // §3.2 的两条（LHS 用显式子查询，保证 LHS 里真的有 group-by）
            "SELECT t1.cust, t1.tot AS s2000, t2.tot AS s2001 "
          + "FROM (SELECT cust, yr_i, SUM(amt_i) AS tot FROM sales GROUP BY cust, yr_i) t1, "
          + "     (SELECT cust, yr_i, SUM(amt_i) AS tot FROM sales GROUP BY cust, yr_i) t2 "
          + "WHERE t1.cust = t2.cust AND t1.yr_i = 2000 AND t2.yr_i = 2001",
            "SELECT cust, "
          + "MAX(CASE WHEN yr_i = 2000 THEN tot END) AS s2000, "
          + "MAX(CASE WHEN yr_i = 2001 THEN tot END) AS s2001 "
          + "FROM (SELECT cust, yr_i, SUM(amt_i) AS tot FROM sales GROUP BY cust, yr_i) "
          + "WHERE cust IS NOT NULL GROUP BY cust "
          + "HAVING SUM(CASE WHEN yr_i = 2000 THEN 1 ELSE 0 END) > 0 "
          + "AND SUM(CASE WHEN yr_i = 2001 THEN 1 ELSE 0 END) > 0",
            // §3.3 的 LHS：两个标量子查询的 CROSS JOIN
            "SELECT a.promo_sales, b.total FROM "
          + "(SELECT SUM(price_i) AS promo_sales FROM s JOIN promo ON s.promo_i = promo.pk_i WHERE promo.ch = 'Y') a, "
          + "(SELECT SUM(price_i) AS total FROM s) b",
            // §3.3 的两条
            "SELECT (SELECT SUM(price_i) FROM s JOIN promo ON s.promo_i = promo.pk_i WHERE promo.ch = 'Y'), "
          + "       (SELECT SUM(price_i) FROM s) FROM (VALUES (0))",
            "SELECT SUM(CASE WHEN p.pk_i IS NOT NULL THEN s.price_i END), SUM(s.price_i) "
          + "FROM s LEFT JOIN promo p ON s.promo_i = p.pk_i AND p.ch = 'Y'",
        };

        for (int i = 0; i < sqls.length; i++) {
            System.out.println("\n================ SQL #" + (i + 1) + " ================");
            System.out.println(sqls[i]);
            System.out.println("---------------- plan ----------------");
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

        CalciteCatalogReader catalogReader = new CalciteCatalogReader(
                CalciteSchema.from(root), Collections.singletonList(""), TF,
                new CalciteConnectionConfigImpl(new Properties()));
        SqlValidator validator = SqlValidatorUtil.newValidator(
                SqlStdOperatorTable.instance(), catalogReader, TF,
                SqlValidator.Config.DEFAULT.withIdentifierExpansion(true));

        SqlNode node = SqlParser.create(sql, SqlParser.config()
                .withCaseSensitive(false)
                .withUnquotedCasing(org.apache.calcite.avatica.util.Casing.TO_LOWER)
                .withQuotedCasing(org.apache.calcite.avatica.util.Casing.UNCHANGED))
                .parseQuery();
        SqlNode validated = validator.validate(node);

        SqlToRelConverter converter = new SqlToRelConverter(
                (org.apache.calcite.plan.RelOptTable.ViewExpander) (rt, qs, sp, vp) -> null,
                validator, catalogReader, cluster, StandardConvertletTable.INSTANCE,
                SqlToRelConverter.config().withTrimUnusedFields(false).withExpand(false));
        RelRoot r = converter.convertQuery(validated, false, true);
        return r.rel;
    }
}
