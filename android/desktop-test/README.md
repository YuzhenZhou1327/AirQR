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

## Rotation-chain harnesses (v1.13–v1.15 hold-orientation work)

### `RotationSim.java` — pure geometry, locks the shipped math
Ports (does not re-implement) `CameraController.applyLetterbox`,
`CameraController.focusPoint` and the `QrGridAnalyzer` transpose rule, and
asserts 6 properties: T1 legacy field anchor, T2 focus round-trip
(holds x mounts x views x taps), T3 letterbox aspect/containment,
T4 grid-cell wholeness + negative control (old no-transpose rule must cut
codes), T5 AOSP display-angle formula table. Uses the real GridCells.

  cd android/desktop-test
  javac -encoding UTF-8 -cp "../libs/core-3.5.3.jar" -d "$T/rotsim" \
    RotationSim.java ../src/com/airqr/camera/GridCells.java
  java -cp "$T/rotsim;../libs/core-3.5.3.jar" RotationSim

### `RotBench.java` — end-to-end transpose proof on REAL rendered frames
Decodes a frame PNG both as-is (landscape hold) and rotated 90° (portrait
hold), with and without transposed grid cells. Expected: landscape as-is
2/2; portrait transposed 2/2 and as-is 0/2 (the negative control that proved
v1.12 never hit R0/R1 in portrait).

  javac -encoding UTF-8 -cp "../libs/core-3.5.3.jar" -d "$T/rotbench" \
    RotBench.java ../src/com/airqr/camera/GridCells.java \
    ../src/com/airqr/camera/HalfSampleLuminanceSource.java
  java -Djava.awt.headless=true -cp "$T/rotbench;../libs/core-3.5.3.jar" \
    RotBench /path/to/frames/p000-f00001.png

`$T` = any scratch dir (Windows classpath separator is ';'). Get a frame PNG
with `airqr render <file> --out frames` (any pass-0 frame works).
