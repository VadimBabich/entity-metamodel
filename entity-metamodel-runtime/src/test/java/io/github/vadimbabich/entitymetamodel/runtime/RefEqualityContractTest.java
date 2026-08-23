package io.github.vadimbabich.entitymetamodel.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.vadimbabich.entitymetamodel.runtime.fixtures.Account;
import org.junit.jupiter.api.Test;

/**
 * Value identity over {@code (entityType, propertyName, alias)} — alias included, because distinct
 * aliases are distinct table instances. The 1.x {@code Column_}'s asymmetric equals is the
 * anti-pattern these tests keep dead.
 */
class RefEqualityContractTest {

  @Test
  void entityRefsWithSameTypeAndAliasAreEqual() {
    EntityRef<Account> first = EntityRef.of(Account.class);
    EntityRef<Account> second = EntityRef.of(Account.class);

    assertThat(first).isEqualTo(second);
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
  }

  @Test
  void aQualifierThatCouldEscapeTheIdentifierIsRejected() {
    EntityRef<Account> account = EntityRef.of(Account.class);

    // The alias reaches the statement as a bare, unquoted identifier. Anything outside the
    // identifier alphabet either breaks the SQL or ends it and appends whatever follows.
    assertThatIllegalArgumentException().isThrownBy(() -> account.as("a b"));
    assertThatIllegalArgumentException().isThrownBy(() -> account.as("a-b"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> account.as("x\" ; drop table accounts --"));
  }

  @Test
  void anAliasQualifierIsLowerCasedBecauseUnquotedIdentifiersFold() {
    EntityRef<Account> account = EntityRef.of(Account.class);

    // A database folds an unquoted alias to lower case, so two qualifiers differing only in case
    // are one table instance there. Treating them as distinct here would mint two refs that both
    // claim the same projected labels.
    assertThat(account.as("Sponsor").alias()).isEqualTo("account_sponsor");
    assertThat(account.as("Sponsor")).isEqualTo(account.as("sponsor"));
  }

  @Test
  void aliasedCopyIsADistinctInstanceWithDistinctIdentity() {
    EntityRef<Account> defaultInstance = EntityRef.of(Account.class);
    EntityRef<Account> secondInstance = defaultInstance.as("2");

    assertThat(secondInstance).isNotEqualTo(defaultInstance);
    assertThat(secondInstance.alias()).isEqualTo("account_2");
    assertThat(defaultInstance.alias()).isEqualTo("account");
  }

  @Test
  void propertyRefEqualityIncludesTheAlias() {
    PropertyRef<Account, Long> onDefault = EntityRef.of(Account.class).property("id", Long.class);
    PropertyRef<Account, Long> onSecond =
        EntityRef.of(Account.class).as("2").property("id", Long.class);
    PropertyRef<Account, Long> onDefaultAgain =
        EntityRef.of(Account.class).property("id", Long.class);

    assertThat(onDefault).isEqualTo(onDefaultAgain);
    assertThat(onDefault.hashCode()).isEqualTo(onDefaultAgain.hashCode());
    assertThat(onDefault).isNotEqualTo(onSecond);
  }

  @Test
  void equalsIsSymmetricAndNullSafeAndTypeSafe() {
    PropertyRef<Account, Long> ref = EntityRef.of(Account.class).property("id", Long.class);
    PropertyRef<Account, Long> same = EntityRef.of(Account.class).property("id", Long.class);

    // The 1.x defect was asymmetry: column_.equals(column) true, column.equals(column_) false.
    assertThat(ref.equals(same)).isEqualTo(same.equals(ref));
    assertThat(ref.equals(null)).isFalse();
    assertThat(ref.equals("id")).isFalse();
  }

  @Test
  void reAnchoringBindsThePropertyToTheOtherInstance() {
    EntityRef<Account> parent = EntityRef.of(Account.class);
    EntityRef<Account> child = parent.as("2");
    PropertyRef<Account, Long> id = parent.property("id", Long.class);

    PropertyRef<Account, Long> reAnchored = id.of(child);

    assertThat(reAnchored.entity()).isEqualTo(child);
    assertThat(reAnchored.name()).isEqualTo("id");
    assertThat(reAnchored).isNotEqualTo(id);
    assertThat(reAnchored).isEqualTo(child.property("id", Long.class));
  }

  @Test
  void theProjectionSeparatorIsReservedInAliases() {
    EntityRef<Account> account = EntityRef.of(Account.class);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> account.as("legacy__copy"))
        .withMessageContaining("__");
  }

  @Test
  void aQualifierThatWouldComposeTheSeparatorIsRejected() {
    EntityRef<Account> account = EntityRef.of(Account.class);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> account.as("_copy"))
        .withMessageContaining("__");
  }

  @Test
  void reAliasingExtendsTheAliasInsteadOfReplacingTheQualifier() {
    EntityRef<Account> account = EntityRef.of(Account.class);

    assertThat(account.as("a").as("b").alias()).isEqualTo("account_a_b");
    assertThat(account.as("a").as("b")).isNotEqualTo(account.as("b"));
  }
}
