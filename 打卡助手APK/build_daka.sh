#!/bin/bash
# 构建「集港打卡助手」原生 APK（不用 Gradle）
set -e
SRC="/c/Users/admin/Desktop/连点器/打卡助手APK"
W="$SRC/_build"
B="/c/Users/admin/Desktop/连点器/sdkdl"
BT="$B/bt/android-16"
AJ="$B/plat/android-36/android.jar"
JDK="/c/Program Files/Java/jdk-17.0.2/bin"
PY="/c/Users/admin/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe"

rm -rf "$W"
mkdir -p "$W/src" "$W/out/classes" "$W/out/dex"
cp -r "$SRC/src/." "$W/src/"
cp "$SRC/AndroidManifest.xml" "$W/AndroidManifest.xml"

echo "== 1/7 编译资源"
# 注意：aapt2 compile 的 --dir 传中文路径会报 "failed to open directory"，
# 所以先 cd 进 res 目录用 "." 作参数
(cd "$SRC/res" && "$BT/aapt2.exe" compile --dir . -o "$(cygpath -w "$W/out/res.zip")")

echo "== 2/7 链接资源"
"$BT/aapt2.exe" link -o "$(cygpath -w "$W/out/base.apk")" -I "$(cygpath -w "$AJ")" \
  --manifest "$(cygpath -w "$W/AndroidManifest.xml")" --min-sdk-version 26 --target-sdk-version 33 \
  "$(cygpath -w "$W/out/res.zip")"

echo "== 3/7 javac"
JAVA_FILES=$(find "$W/src" -name '*.java' -exec cygpath -w {} \;)
"$JDK/javac.exe" -source 8 -target 8 -encoding UTF-8 -classpath "$(cygpath -w "$AJ")" \
  -d "$(cygpath -w "$W/out/classes")" $JAVA_FILES

echo "== 4/7 d8"
CLASS_FILES=$(find "$W/out/classes" -name '*.class' -exec cygpath -w {} \;)
"$JDK/java.exe" -cp "$(cygpath -w "$BT/lib/d8.jar")" com.android.tools.r8.D8 --release \
  --lib "$(cygpath -w "$AJ")" --min-api 26 --output "$(cygpath -w "$W/out/dex")" $CLASS_FILES

echo "== 5/7 塞 classes.dex"
"$PY" - "$(cygpath -w "$W/out/base.apk")" "$(cygpath -w "$W/out/dex/classes.dex")" <<'EOF'
import sys, zipfile, shutil
apk, dex = sys.argv[1], sys.argv[2]
shutil.copy(apk, apk + ".tmp")
with zipfile.ZipFile(apk + ".tmp", "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
shutil.move(apk + ".tmp", apk)
print("classes.dex 已写入")
EOF

echo "== 6/7 zipalign"
"$BT/zipalign.exe" -f 4 "$(cygpath -w "$W/out/base.apk")" "$(cygpath -w "$W/out/aligned.apk")"

echo "== 7/7 apksigner"
"$BT/apksigner.bat" sign --ks "$(cygpath -w "$B/debug.keystore")" --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  --out "$(cygpath -w "$W/out/daka.apk")" "$(cygpath -w "$W/out/aligned.apk")"

ls -la "$W/out/daka.apk"
"$BT/aapt2.exe" dump badging "$(cygpath -w "$W/out/daka.apk")" | head -4
