package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DepthCalibrationTest {

  // The reproducible worst case: "cold" is not a state, since the ceiling climbs several-fold as
  // the JIT warms, and a warm JVM passes at heights a cold one cannot. Forked because Surefire
  // shares one JVM.
  private static final String C1_ONLY = "-XX:TieredStopAtLevel=1";

  // A realistic application stack already in place: at this depth height 64 overflows and 32 holds.
  private static final String CALLER_FRAMES = "300";

  @Test
  void theSupportedDepthRendersInAC1OnlyJvmBehindThreeHundredCallerFrames(@TempDir Path scratch)
      throws Exception {
    String javaBinary = ProcessHandle.current().info().command().orElseThrow();
    Path childOutput = scratch.resolve("calibration.log");
    Process calibration =
        new ProcessBuilder(
            javaBinary,
            C1_ONLY,
            "-cp",
            System.getProperty("java.class.path"),
            RenderDepthCalibration.class.getName(),
            CALLER_FRAMES,
            String.valueOf(DeepConditions.SUPPORTED_NESTING))
            .redirectErrorStream(true)
            .redirectOutput(childOutput.toFile())
            .start();

    try {
      boolean finished = calibration.waitFor(2, TimeUnit.MINUTES);

      assertThat(finished).isTrue();
      assertThat(calibration.exitValue())
          .describedAs(Files.readString(childOutput, StandardCharsets.UTF_8))
          .isZero();
    } finally {
      calibration.destroyForcibly();
    }
  }
}
