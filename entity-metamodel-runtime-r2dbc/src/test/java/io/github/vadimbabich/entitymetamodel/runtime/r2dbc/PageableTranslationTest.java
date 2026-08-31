package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.MEMBERSHIP;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.SPONSOR;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.sponsoringAccount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Membership;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;

/**
 * A {@code Pageable} carries property names as text, the one place the typed metamodel cannot
 * reach. What the translation refuses matters as much as what it accepts: a dropped sort option
 * returns rows in an order the caller did not ask for, with no error anywhere.
 */
class PageableTranslationTest {

  private static final EntityRef<Account> ACCOUNT = EntityRef.of(Account.class);

  private final R2dbcMappingContext mappingContext = new R2dbcMappingContext();
  private final QueryRenderer renderer =
      new QueryRenderer(mappingContext, PostgresDialect.INSTANCE);
  private final PageableTranslator translator = new PageableTranslator(mappingContext);

  private String sqlOf(Pageable pageable) {
    return renderer.render(translator.applyTo(FluentSelect.from(ACCOUNT), pageable)).sql();
  }

  private String probingSqlOf(Pageable pageable) {
    return renderer
        .render(translator.applyToWithProbeRow(FluentSelect.from(ACCOUNT), pageable).select())
        .sql();
  }

  @Test
  void aPageRequestBecomesTheLimitAndOffsetOfThatPage() {
    assertThat(sqlOf(PageRequest.of(2, 25))).endsWith("LIMIT 25 OFFSET 50");
  }

  @Test
  void aSortedPageRequestOrdersByTheNamedPropertysColumn() {
    Pageable secondPageByEmail =
        PageRequest.of(1, 10, Sort.by(Sort.Direction.DESC, "ownerEmail"));

    assertThat(sqlOf(secondPageByEmail))
        .contains("ORDER BY \"account\".\"owner_email\" DESC")
        .endsWith("LIMIT 10 OFFSET 10");
  }

  @Test
  void severalSortTermsKeepThePageablesOrder() {
    Pageable byEmailThenId =
        PageRequest.of(0, 5, Sort.by("ownerEmail").ascending().and(Sort.by("id").descending()));

    assertThat(sqlOf(byEmailThenId))
        .contains("ORDER BY \"account\".\"owner_email\" ASC, \"account\".\"account_id\" DESC");
  }

  @Test
  void anUnpagedRequestBoundsNothingBecauseItAsksForEverything() {
    assertThat(sqlOf(Pageable.unpaged())).doesNotContain("LIMIT").doesNotContain("OFFSET");
  }

  @Test
  void anUnpagedRequestStillHonoursItsSort() {
    assertThat(sqlOf(Pageable.unpaged(Sort.by("ownerEmail"))))
        .endsWith("ORDER BY \"account\".\"owner_email\" ASC");
  }

  @Test
  void aRequestedSortLeadsTheDescriptionsOwnRatherThanTrailingIt() {
    // A reusable description often carries a unique tiebreaker, behind which the request's sort is
    // unreachable and silently ineffective for every column.
    FluentSelect<Account> byIdDescending =
        FluentSelect.from(ACCOUNT).orderBy(ACCOUNT.property("id", Long.class).desc());

    String sql =
        renderer
            .render(
                translator.applyTo(
                    byIdDescending, PageRequest.of(0, 10, Sort.by("ownerEmail"))))
            .sql();

    assertThat(sql)
        .contains(
            "ORDER BY \"account\".\"owner_email\" ASC, \"account\".\"account_id\" DESC");
  }

  @Test
  void anUnsortedRequestLeavesTheDescriptionsOwnSortAlone() {
    FluentSelect<Account> byIdDescending =
        FluentSelect.from(ACCOUNT).orderBy(ACCOUNT.property("id", Long.class).desc());

    String sql = renderer.render(translator.applyTo(byIdDescending, PageRequest.of(0, 10))).sql();

    assertThat(sql).contains("ORDER BY \"account\".\"account_id\" DESC");
  }

  @Test
  void aPagedRequestOverADescriptionThatAlreadyBoundsItselfIsRefused() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> translator.applyTo(FluentSelect.from(ACCOUNT).limit(500), PageRequest.of(0, 10)))
        .withMessageContaining("limit");

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> translator.applyTo(FluentSelect.from(ACCOUNT).offset(10), PageRequest.of(0, 10)))
        .withMessageContaining("offset");
  }

  @Test
  void anUnpagedRequestLeavesTheDescriptionsOwnWindowAlone() {
    // No window from the request, so the paged case is the only one that collides.
    String sql =
        renderer
            .render(translator.applyTo(FluentSelect.from(ACCOUNT).limit(2).offset(1),
                Pageable.unpaged()))
            .sql();

    assertThat(sql).endsWith("LIMIT 2 OFFSET 1");
  }

  @Test
  void aSortOnSomethingTheEntityDoesNotPersistIsRejected() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> sqlOf(PageRequest.of(0, 10, Sort.by("nickname"))))
        .withMessageContaining("nickname");
  }

  /**
   * A name only the joined side persists is refused, but a name both tables persist orders by the
   * root's column with nothing to notice — including under the mapper form of {@code page}.
   */
  @Test
  void aTextSortResolvesAgainstTheRootEvenWhereAJoinedInstanceIsSelected() {
    FluentSelect<Membership> sponsored =
        FluentSelect.from(MEMBERSHIP).join(sponsoringAccount(), SPONSOR).alsoSelect(SPONSOR);

    String sql =
        renderer.render(translator.applyTo(sponsored, PageRequest.of(0, 2, Sort.by("id")))).sql();

    assertThat(sql)
        .contains("ORDER BY \"membership\".\"membership_id\" ASC")
        .doesNotContain("\"account_sponsor\".\"account_id\" ASC");

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> translator.applyTo(sponsored, PageRequest.of(0, 2, Sort.by("ownerEmail"))))
        .withMessageContaining("ownerEmail");
  }

  @Test
  void aSortOptionThisVocabularyCannotExpressIsRefusedRatherThanDropped() {
    Pageable ignoringCase =
        PageRequest.of(
            0, 10, Sort.by(new Sort.Order(Sort.Direction.ASC, "ownerEmail").ignoreCase()));
    Pageable nullsLast =
        PageRequest.of(
            0, 10, Sort.by(new Sort.Order(Sort.Direction.ASC, "ownerEmail").nullsLast()));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> sqlOf(ignoringCase))
        .withMessageContaining("ExpressionSort");

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> sqlOf(nullsLast))
        .withMessageContaining("ExpressionSort");
  }

  @Test
  void aMissingDescriptionOrRequestIsRejectedWhereItIsPassed() {
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> translator.applyTo(null, Pageable.unpaged()));
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> translator.applyTo(FluentSelect.from(ACCOUNT), null));
  }

  @Test
  void aProbingRequestAsksForOneRowMoreThanThePageItWindows() {
    assertThat(probingSqlOf(PageRequest.of(2, 25))).endsWith("LIMIT 26 OFFSET 50");
  }

  @Test
  void aProbingRequestReportsTheRowsItWindowedTheProbeRowBeyond() {
    // slice(...) trims to this rather than to the request, so the two cannot drift apart.
    assertThat(
            translator
                .applyToWithProbeRow(FluentSelect.from(ACCOUNT), PageRequest.of(2, 25))
                .rowsPerPage())
        .hasValue(25L);

    assertThat(
            translator
                .applyToWithProbeRow(FluentSelect.from(ACCOUNT), Pageable.unpaged())
                .rowsPerPage())
        .isEmpty();
  }

  @Test
  void aProbingRequestForTheLargestLegalPageStillRenders() {
    // In int arithmetic the probe row wraps this page size to a negative limit.
    assertThat(probingSqlOf(Pageable.ofSize(Integer.MAX_VALUE)))
        .endsWith("LIMIT 2147483648 OFFSET 0");
  }

  @Test
  void anUnpagedProbingRequestNeverReachesThePageSize() {
    // Pageable.unpaged().getPageSize() throws, so the unpaged branch has to return before any
    // arithmetic on it.
    assertThat(probingSqlOf(Pageable.unpaged())).doesNotContain("LIMIT").doesNotContain("OFFSET");
  }

  @Test
  void aProbingRequestOverADescriptionThatAlreadyBoundsItselfIsRefused() {
    // The probe row is a second window as much as the page is, so it inherits the same refusal.
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () ->
                translator.applyToWithProbeRow(
                    FluentSelect.from(ACCOUNT).limit(500), PageRequest.of(0, 10)))
        .withMessageContaining("limit");

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () ->
                translator.applyToWithProbeRow(
                    FluentSelect.from(ACCOUNT).offset(10), PageRequest.of(0, 10)))
        .withMessageContaining("offset");
  }

  @Test
  void aProbingRequestsSortLeadsTheDescriptionsOwnJustAsAPagesDoes() {
    FluentSelect<Account> byIdDescending =
        FluentSelect.from(ACCOUNT).orderBy(ACCOUNT.property("id", Long.class).desc());

    String sql =
        renderer
            .render(
                translator.applyToWithProbeRow(
                        byIdDescending, PageRequest.of(0, 10, Sort.by("ownerEmail")))
                    .select())
            .sql();

    assertThat(sql)
        .contains("ORDER BY \"account\".\"owner_email\" ASC, \"account\".\"account_id\" DESC");
  }

  @Test
  void aProbingRequestRejectsAMissingDescriptionOrRequest() {
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> translator.applyToWithProbeRow(null, Pageable.unpaged()));
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> translator.applyToWithProbeRow(FluentSelect.from(ACCOUNT), null));
  }
}
