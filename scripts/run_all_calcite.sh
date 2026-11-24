#!/bin/bash
set -u
cd /root/QO-Verify-validation/calcite || exit 1
CP="$(cat cp.txt)"
JVM="java --add-opens=java.base/java.util=ALL-UNNAMED"

{
  echo "===== 主实验 A. 归一化 + 完整规则集 ====="
  $JVM -Dqov.csv=/root/QO-Verify-validation/results.csv -cp "target/classes:$CP" \
       demo.qoverify.QoVerifyDemo 2>/dev/null
  echo
  echo "===== 主实验 B. 消融：去掉 JOIN 交换/结合 ====="
  $JVM -Dqov.csv=/tmp/b.csv -cp "target/classes:$CP" \
       demo.qoverify.QoVerifyDemo --nojoinrules 2>/dev/null
  echo
  echo "===== §3.2 / §3.3 的 RelOptRule ====="
  $JVM -cp "target/classes:$CP" demo.qoverify.RuleDemo 2>/dev/null
} > /root/QO-Verify-validation/results/RESULTS_calcite.txt 2>&1

cat /root/QO-Verify-validation/results/RESULTS_calcite.txt
