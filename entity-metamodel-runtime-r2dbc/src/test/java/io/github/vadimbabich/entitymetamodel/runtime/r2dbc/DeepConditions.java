package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.accountId;

import io.github.vadimbabich.entitymetamodel.runtime.Condition;
import java.util.concurrent.atomic.AtomicReference;

final class DeepConditions {

  static final int SUPPORTED_NESTING = QueryRenderer.MAX_CONDITION_DEPTH;

  // The smallest stack the library treats as supported, within a quarter of the JVM's own minimum.
  // Every ceiling behind the depth constant was measured here; raising it re-opens that constant.
  static final int SMALL_STACK_KILOBYTES = 256;

  private DeepConditions() {
  }

  static Condition alternatingOfHeight(int height) {
    return alternatingOver(accountId().gt(0L), height);
  }

  static Condition alternatingOver(Condition deepestLeaf, int levelsAbove) {
    Condition condition = deepestLeaf;

    for (int level = 1; level <= levelsAbove; level++) {
      Condition threshold = accountId().gt((long) level);
      if (level % 2 == 0) {
        condition = condition.and(threshold);
      } else {
        condition = condition.or(threshold);
      }
    }

    return condition;
  }

  static Condition negatedTimes(int negations) {
    Condition condition = accountId().gt(0L);

    for (int applied = 0; applied < negations; applied++) {
      condition = condition.not();
    }

    return condition;
  }

  static Condition flatAndOfTerms(int terms) {
    Condition condition = accountId().gt(0L);

    for (long threshold = 1; threshold < terms; threshold++) {
      condition = condition.and(accountId().gt(threshold));
    }

    return condition;
  }

  static Throwable renderOnSmallStack(int callerFrames, FluentSelect<?> select)
      throws InterruptedException {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread constrained =
        new Thread(
            null,
            () -> descendThenRender(callerFrames, select),
            "render-on-small-stack",
            SMALL_STACK_KILOBYTES * 1024L);
    constrained.setUncaughtExceptionHandler((thread, thrown) -> failure.set(thrown));

    constrained.start();
    constrained.join();

    return failure.get();
  }

  private static void descendThenRender(int remainingFrames, FluentSelect<?> select) {
    if (remainingFrames > 0) {
      descendThenRender(remainingFrames - 1, select);
      return;
    }

    TestRenderers.postgres().render(select);
  }
}
