#!/bin/bash
# 环境准备（Ubuntu 22.04 / 24.04）
set -euo pipefail

sudo apt-get update -qq
sudo apt-get install -y -qq --no-install-recommends \
    openjdk-21-jdk-headless maven python3 git curl unzip

echo "--- versions ---"
java -version 2>&1 | head -1
mvn -v 2>&1 | head -1
python3 -V

# Maven 使用阿里云镜像（国内）：
#   cp calcite/settings.xml ~/.m2/settings.xml
echo
echo "提示：国内建议执行  mkdir -p ~/.m2 && cp calcite/settings.xml ~/.m2/settings.xml"
