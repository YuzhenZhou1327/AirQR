# Desktop self-test harness (no phone needed)

Replicates the phone's mock self-test path byte-for-byte on a desktop JVM
using the SAME shipped files (no Android framework):

- `../src/com/airqr/camera/Nv21.java` — pure-Java, zero Android imports
- `../src/com/airqr/core/Wire.java` — pure-Java, zero Android imports
- `../libs/core-3.5.3.jar` — the exact zxing jar dexed into the APK
- `SelfTestHarness.java` — mirrors `QrGridAnalyzer` stages + hints exactly

Run (JDK 11+, headless OK):

    H=.../Temp/airqr-harness; ZX=../libs/core-3.5.3.jar
    javac -encoding UTF-8 -cp "$ZX" -d "$H/classes" \
      ../src/com/airqr/camera/Nv21.java ../src/com/airqr/core/Wire.java \
      SelfTestHarness.java
    java -Djava.awt.headless=true -cp "$H/classes;$ZX" SelfTestHarness \
      ../assets/testqr.png [maxWidth] [dumpPath]

What it proves: PNG → NV21 → rotate → decode → ISO-8859-1 → Wire.parseBlock.
History: caught the v1.7 root cause — zxing-java `getRawBytes()` returns ALL
data codewords WITH mode/length headers (274B for a 43B payload), so the app
must use `getText()`+ISO-8859-1 (byte-identical vs zxing-cpp, verified here).
