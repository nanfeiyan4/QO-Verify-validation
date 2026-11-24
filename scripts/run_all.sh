#!/bin/bash
# 一键跑完 Calcite 验证 + fuzz（在仓库根目录执行）
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/calcite" || exit 1

CP="$(cat cp.txt 2>/dev/null || true)"
if [ -z "$CP" ]; then
  mvn -q -B dependency:build-classpath -Dmdep.outputFile=cp.txt >/dev/null 2>&1
  CP="$(cat cp.txt)"
fi

JVM="java --add-opens=java.base/java.util=ALL-UNNAMED"
CSV="$ROOT/results.csv"

{
  echo "===== A. 归一化 + 完整规则集 ====="
  $JVM -Dqov.csv="$CSV" -cp "target/classes:$CP" demo.qoverify.QoVerifyDemo 2>/dev/null
  echo
  echo "===== B. 消融：去掉 JOIN 交换/结合 ====="
  $JVM -Dqov.csv=/tmp/qov_b.csv -cp "target/classes:$CP" demo.qoverify.QoVerifyDemo --nojoinrules 2>/dev/null
} | tee "$ROOT/results/RESULTS_calcite.txt"

echo
echo "--- 判定 CSV ---"
cat "$CSV"

echo
echo "===== fuzz ====="
cd "$ROOT/fuzz" || exit 1
# fuzz_qoverify.py 默认读 /root/qoverify/results.csv，这里用环境变量覆盖
QOV_CSV="$CSV" python3 fuzz_qoverify.py 1000 | tee "$ROOT/results/RESULTS_fuzz.txt"

echo
echo "===== §3.2 / §3.3 规则 ====="
cd "$ROOT/toy" || exit 1
python3 fig2_qoverify.py  > "$ROOT/results/RESULTS_toy.txt" 2>&1
python3 rules_32_33.py    > "$ROOT/results/RESULTS_rules_toy.txt" 2>&1
cd "$ROOT/fuzz" || exit 1
python3 fuzz_rules.py     > "$ROOT/results/RESULTS_rules_fuzz.txt" 2>&1
echo "done. see results/"
