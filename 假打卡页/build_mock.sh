#!/bin/bash
# 构建「假打卡页」APK
set -e
SRC="/c/Users/admin/Desktop/连点器/假打卡页"
W="/c/Users/admin/Desktop/连点器/假打卡页/_build"
B="/c/Users/admin/Desktop/连点器/sdkdl"
BT="$B/bt/android-16"
AJ="$B/plat/android-36/android.jar"
JDK="/c/Program Files/Java/jdk-17.0.2/bin"
PY="/c/Users/admin/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe"

rm -rf "$W"
mkdir -p "$W/src" "$W/out/classes" "$W/out/dex"
cp -r "$SRC/src/." "$W/src/"
cp "$SRC/AndroidManifest.xml" "$W/AndroidManifest.xml"

echo "== 1/6 aapt2 link"
"$BT/aapt2.exe" link -o "$(cygpath -w "$W/out/base.apk")" -I "$(cygpath -w "$AJ")" \
  --manifest "$(cygpath -w "$W/AndroidManifest.xml")" --min-sdk-version 26 --target-sdk-version 33

echo "== 2/6 javac"
JAVA_FILES=$(find "$W/src" -name '*.java' -exec cygpath -w {} \;)
"$JDK/javac.exe" -source 8 -target 8 -encoding UTF-8 -classpath "$(cygpath -w "$AJ")" \
  -d "$(cygpath -w "$W/out/classes")" $JAVA_FILES

echo "== 3/6 d8"
CLASS_FILES=$(find "$W/out/classes" -name '*.class' -exec cygpath -w {} \;)
"$JDK/java.exe" -cp "$(cygpath -w "$BT/lib/d8.jar")" com.android.tools.r8.D8 --release \
  --lib "$(cygpath -w "$AJ")" --min-api 26 --output "$(cygpath -w "$W/out/dex")" $CLASS_FILES

echo "== 4/6 classes.dex -> apk"
"$PY" - "$(cygpath -w "$W/out/base.apk")" "$(cygpath -w "$W/out/dex/classes.dex")" <<'EOF'
import sys, zipfile, shutil
apk, dex = sys.argv[1], sys.argv[2]
shutil.copy(apk, apk + ".tmp")
with zipfile.ZipFile(apk + ".tmp", "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
shutil.move(apk + ".tmp", apk)
print("classes.dex 已写入")
EOF

echo "== 5/6 zipalign"
"$BT/zipalign.exe" -f 4 "$(cygpath -w "$W/out/base.apk")" "$(cygpath -w "$W/out/aligned.apk")"

echo "== 6/6 apksigner"
"$BT/apksigner.bat" sign --ks "$(cygpath -w "$B/debug.keystore")" --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  --out "$(cygpath -w "$W/out/mockdaka.apk")" "$(cygpath -w "$W/out/aligned.apk")"

ls -la "$W/out/mockdaka.apk"
"$BT/aapt2.exe" dump badging "$(cygpath -w "$W/out/mockdaka.apk")" | head -3
