package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.Predicate;
import io.github.vadimbabich.entitymetamodel.runtime.PropertyRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PropertyRef direct call tests")
class PropertyRefDirectCallTest {

  static class Account {
    public Long id;
    public String email;
  }

  @Test
  @DisplayName("should call PropertyRef.is() directly")
  void callPropertyRefDirectly() {
    try {
      EntityRef<Account> entity = EntityRef.of(Account.class);
      PropertyRef<Account, String> email = entity.property("email", String.class);

      Predicate result = email.is("test@example.com");
      assertThat(result).isNotNull();
      assertThat(result).isInstanceOf(Condition.class);
    } catch (Exception e) {
      e.printStackTrace();
      fail("Failed to call PropertyRef.is()", e);
    }
  }
}
