package io.github.vadimbabich.entitymetamodel.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Locks the token pairings a generated metamodel emits. The erased one is what forces
 * {@code Class<?>}: tighten the parameter to {@code Class<T>} and {@code List.class} against
 * {@code List<String>} stops compiling. The primitive tokens would survive that change — they are
 * here because the token must reach {@code declaredRawType()} as declared rather than boxed.
 */
class DeclaredTypeTokenTest {

  private record Payment(long id, boolean captured) {}

  private record Inventory(List<String> tags, int[] scores) {}

  private static final EntityRef<Payment> PAYMENT = EntityRef.of(Payment.class);
  private static final EntityRef<Inventory> INVENTORY = EntityRef.of(Inventory.class);

  @Test
  void aPrimitiveTokenPairsWithABoxedContract() {
    PropertyRef<Payment, Long> id = PAYMENT.property("id", long.class);
    PropertyRef<Payment, Boolean> captured = PAYMENT.property("captured", boolean.class);

    assertThat(id.declaredRawType()).isEqualTo(long.class);
    assertThat(captured.declaredRawType()).isEqualTo(boolean.class);
  }

  @Test
  void anErasedTokenPairsWithAParameterizedContract() {
    PropertyRef<Inventory, List<String>> tags = INVENTORY.property("tags", List.class);

    assertThat(tags.declaredRawType()).isEqualTo(List.class);
  }

  @Test
  void anArrayTokenSurvivesAsAnArrayType() {
    PropertyRef<Inventory, int[]> scores = INVENTORY.property("scores", int[].class);

    assertThat(scores.declaredRawType()).isEqualTo(int[].class);
  }

  /** The token is not identity: two handles to one property are equal whatever it says. */
  @Test
  void theTokenDoesNotParticipateInEquality() {
    PropertyRef<Payment, Long> declared = PAYMENT.property("id", long.class);
    PropertyRef<Payment, Long> boxed = PAYMENT.property("id", Long.class);

    assertThat(declared).isEqualTo(boxed).hasSameHashCodeAs(boxed);
  }
}
