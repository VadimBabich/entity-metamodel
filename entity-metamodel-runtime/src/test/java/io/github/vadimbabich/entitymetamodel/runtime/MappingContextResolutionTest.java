package io.github.vadimbabich.entitymetamodel.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.vadimbabich.entitymetamodel.runtime.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.fixtures.Child;
import io.github.vadimbabich.entitymetamodel.runtime.fixtures.Invoice;
import io.github.vadimbabich.entitymetamodel.runtime.fixtures.Parent;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.mapping.model.SimpleTypeHolder;
import org.springframework.data.relational.core.mapping.NamingStrategy;
import org.springframework.data.relational.core.mapping.RelationalMappingContext;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;

/**
 * Resolution delegates to Spring's mapping context: SQL names are never re-implemented and never
 * baked into a ref. With no static state, a ref answers only for the context it was handed.
 */
class MappingContextResolutionTest {

  @Test
  void columnOverrideAndStrategyFallbackBothResolveThroughTheContext() {
    RelationalMappingContext mappingContext = new RelationalMappingContext();
    EntityRef<Account> account = EntityRef.of(Account.class);

    // @Column wins over any strategy; ownerEmail falls back to the default snake_case strategy.
    assertThat(account.property("id", Long.class).columnName(mappingContext))
        .isEqualTo("account_id");
    assertThat(account.property("ownerEmail", String.class).columnName(mappingContext))
        .isEqualTo("owner_email");
  }

  @Test
  void twoContextsAnswerIndependently_theS1IsolationProperty() {
    RelationalMappingContext defaultNaming = new RelationalMappingContext();
    RelationalMappingContext shoutingNaming = new RelationalMappingContext(new NamingStrategy() {
      @Override
      public String getColumnName(RelationalPersistentProperty property) {
        return property.getName().toUpperCase(Locale.ROOT);
      }
    });

    PropertyRef<Account, String> ownerEmail =
        EntityRef.of(Account.class).property("ownerEmail", String.class);

    // The 1.x static holder failed exactly this: one ref, two contexts, no shared state.
    assertThat(ownerEmail.columnName(defaultNaming)).isEqualTo("owner_email");
    assertThat(ownerEmail.columnName(shoutingNaming)).isEqualTo("OWNEREMAIL");
    assertThat(ownerEmail.columnName(defaultNaming)).isEqualTo("owner_email");
  }

  @Test
  void unknownPropertyFailsFastWithEntityAndPropertyInTheMessage() {
    RelationalMappingContext mappingContext = new RelationalMappingContext();
    PropertyRef<Account, String> bogus =
        EntityRef.of(Account.class).property("nope", String.class);

    // The 1.x shape threw lazily at first dereference, deep inside rendering.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> bogus.columnName(mappingContext))
        .withMessageContaining("nope")
        .withMessageContaining("Account");
  }

  @Test
  void aRelationshipPropertyFailsFastInsteadOfNamingAColumnThatDoesNotExist() {
    RelationalMappingContext mappingContext = new RelationalMappingContext();

    // Both are persistent, so the unknown-property guard does not fire — but neither is a column of
    // the parent's table.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> EntityRef.of(Parent.class)
            .property("children", List.class).columnName(mappingContext))
        .withMessageContaining("children")
        .withMessageContaining("Parent");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> EntityRef.of(Parent.class)
            .property("soleChild", Child.class).columnName(mappingContext))
        .withMessageContaining("soleChild");
  }

  @Test
  void aConvertedValueTypeResolvesOnceTheContextKnowsItAsASimpleType() {
    RelationalMappingContext unwired = new RelationalMappingContext();
    PropertyRef<Invoice, Invoice.Money> refund =
        EntityRef.of(Invoice.class).property("refund", Invoice.Money.class);

    // One column or another aggregate is the context's answer, so the refusal says what changes it.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> refund.columnName(unwired))
        .withMessageContaining("simple type");

    RelationalMappingContext wired = new RelationalMappingContext();
    wired.setSimpleTypeHolder(new SimpleTypeHolder(Set.of(Invoice.Money.class), true));

    assertThat(refund.columnName(wired)).isEqualTo("refund");
  }

  @Test
  void anEmbeddedPropertyIsRefusedAsAnEmbeddedValueNotAsARelationship() {
    RelationalMappingContext mappingContext = new RelationalMappingContext();

    // The context calls an embedded value an entity too, but its columns are in this table, so a
    // referenced-table answer would be confidently wrong.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> EntityRef.of(Invoice.class)
            .property("total", Invoice.Money.class).columnName(mappingContext))
        .withMessageContaining("total")
        .withMessageContaining("embedded")
        .withMessageNotContaining("referenced table");
  }

  @Test
  void nameIsTheCompileTimeAnswerAndNeedsNoContext() {
    assertThat(EntityRef.of(Account.class).property("ownerEmail", String.class).name())
        .isEqualTo("ownerEmail");
  }
}
