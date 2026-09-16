package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;

public final class RenderDepthCalibration {

  private RenderDepthCalibration() {
  }

  public static void main(String[] args) throws InterruptedException {
    int callerFrames = Integer.parseInt(args[0]);
    int height = Integer.parseInt(args[1]);

    FluentSelect<Account> select =
        FluentSelect.from(ProductionPatterns.ACCOUNT)
            .where(DeepConditions.alternatingOfHeight(height));

    Throwable failure = DeepConditions.renderOnSmallStack(callerFrames, select);
    if (failure != null) {
      failure.printStackTrace(System.err);
      System.exit(1);
    }

    System.exit(0);
  }
}
