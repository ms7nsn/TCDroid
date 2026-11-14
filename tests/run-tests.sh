#!/usr/bin/env bash
# Tests JVM du cœur (crypto + FAT). Nécessite JDK 17+, python3 + `pip install cryptography`, curl.
set -euo pipefail
cd "$(dirname "$0")"
BC_VER=1.78.1
BC=out/bcprov.jar
mkdir -p out/classes
[ -f "$BC" ] || curl -fsSL -o "$BC" "https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/$BC_VER/bcprov-jdk18on-$BC_VER.jar"
cp FileStorage.java.txt out/FileStorage.java
javac -nowarn -cp "$BC" -d out/classes $(find ../app/src/main/java/com/tcdroid/core ../app/src/main/java/com/tcdroid/fat -name '*.java') out/FileStorage.java CoreTest.java FatTest.java 2>&1 | grep -v '^Note' || true
pip install -q cryptography >/dev/null 2>&1 || true
cd out && cp ../mkvol.py . && python3 mkvol.py gen py.tc
java -Dstdout.encoding=UTF-8 -cp "classes:../$BC" CoreTest
python3 mkvol.py verify py.tc
java -Dstdout.encoding=UTF-8 -cp "classes:../$BC" FatTest
