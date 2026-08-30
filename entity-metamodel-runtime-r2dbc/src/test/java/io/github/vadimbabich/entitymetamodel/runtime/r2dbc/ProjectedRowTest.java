package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ACCOUNT;
import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.SPONSOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.vadimbabich.entitymetamodel.runtime.EntityRef;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Membership;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.data.relational.core.mapping.Column;

/**
 * Reading entities out of one row, keyed by instance rather than by class — {@link
 * #twoInstancesOfOneTableHydrateTheirOwnValues()} is the case the 1.x shape cannot do at all, since
 * reading by column name gives both instances the first one's values.
 */
class ProjectedRowTest {

  private static final R2dbcMappingContext MAPPING_CONTEXT = new R2dbcMappingContext();

  // The ordinary assignment; a statement that renames them is AliasFallbackTest's subject.
  private static ProjectedRow rowOf(Map<String, Object> columns) {
    MappingR2dbcConverter converter = new MappingR2dbcConverter(MAPPING_CONTEXT);

    return new ProjectedRow(
        columns,
        converter,
        StatementAliases.declared(List.of(ACCOUNT, SPONSOR)),
        new ProjectionResolver(converter));
  }

  private static ProjectedRow anAccountRow() {
    return rowOf(columns("account__account_id", 7L, "account__owner_email", "owner@example.com"));
  }

  private static ProjectedRow aMembershipOnlyRow() {
    return rowOf(columns("membership__membership_id", 10L));
  }

  private static ProjectedRow anOwnerAndSponsorRow() {
    return rowOf(
        columns(
            "account__account_id", 1L,
            "account__owner_email", "first@example.com",
            "account_sponsor__account_id", 2L,
            "account_sponsor__owner_email", "second@example.com"));
  }

  private static ProjectedRow anAbsentAccountRow() {
    return rowOf(columns("account__account_id", null, "account__owner_email", null));
  }

  private static Map<String, Object> columns(Object... labelsAndValues) {
    Map<String, Object> row = new LinkedHashMap<>();

    for (int pair = 0; pair < labelsAndValues.length; pair += 2) {
      row.put((String) labelsAndValues[pair], labelsAndValues[pair + 1]);
    }

    return row;
  }

  @Test
  void anInstanceIsHydratedFromItsOwnProjectedColumns() {
    ProjectedRow row = anAccountRow();

    Account account = row.read(ACCOUNT);

    assertThat(account.id).isEqualTo(7L);
    assertThat(account.ownerEmail).isEqualTo("owner@example.com");
  }

  @Test
  void twoInstancesOfOneTableHydrateTheirOwnValues() {
    ProjectedRow row = anOwnerAndSponsorRow();

    Account owner = row.read(ACCOUNT);
    Account sponsoring = row.read(SPONSOR);

    assertThat(owner.id).isEqualTo(1L);
    assertThat(owner.ownerEmail).isEqualTo("first@example.com");
    assertThat(sponsoring.id).isEqualTo(2L);
    assertThat(sponsoring.ownerEmail).isEqualTo("second@example.com");
  }

  @Test
  void anInstanceWhoseColumnsAreAllNullIsAbsentRatherThanBlank() {
    ProjectedRow row = anAbsentAccountRow();

    assertThat(row.readOptional(ACCOUNT)).isEmpty();
  }

  @Test
  void readInsistsOnAnInstanceThatIsActuallyThere() {
    ProjectedRow row = anAbsentAccountRow();

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> row.read(ACCOUNT))
        .withMessageContaining("readOptional");
  }

  @Test
  void anInstanceThatWasNeverProjectedIsRejected() {
    ProjectedRow row = aMembershipOnlyRow();

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> row.read(ACCOUNT))
        .withMessageContaining("account__");
  }

  interface OwnerEmail {
    String getOwnerEmail();
  }

  interface ShoutedEmail {
    @Value("#{target.ownerEmail + '!'}")
    String getLoud();
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  @Value("#{target.ownerEmail + '!'}")
  @interface Shouted {
  }

  interface ShoutedByMetaAnnotation {
    @Shouted
    String getLoud();
  }

  record AccountSummary(Long id, String ownerEmail) {
  }

  @Test
  void aClosedInterfaceProjectionReadsTheColumnsItDeclares() {
    OwnerEmail projected = anAccountRow().readProjection(ACCOUNT, OwnerEmail.class);

    assertThat(projected.getOwnerEmail()).isEqualTo("owner@example.com");
  }

  @Test
  void aRecordProjectionIsHydratedFromTheSameInstance() {
    AccountSummary summary = anAccountRow().readProjection(ACCOUNT, AccountSummary.class);

    assertThat(summary.id()).isEqualTo(7L);
    assertThat(summary.ownerEmail()).isEqualTo("owner@example.com");
  }

  @Test
  void aProjectionOfAnAbsentInstanceIsEmptyRatherThanAProxyOfNulls() {
    ProjectedRow row = anAbsentAccountRow();

    assertThat(row.readProjectionOptional(ACCOUNT, OwnerEmail.class)).isEmpty();

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> row.readProjection(ACCOUNT, OwnerEmail.class))
        .withMessageContaining("readProjectionOptional");
  }

  @Test
  void aProjectionOfAnInstanceThatWasNeverProjectedIsRejected() {
    ProjectedRow row = aMembershipOnlyRow();

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> row.readProjection(ACCOUNT, OwnerEmail.class))
        .withMessageContaining("account__");
  }

  /**
   * The substrate would serve an open projection; refusing it is what keeps expression evaluation
   * out of this door's contract.
   */
  @Test
  void anOpenProjectionIsRefusedRatherThanQuietlyEvaluated() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> anAccountRow().readProjection(ACCOUNT, ShoutedEmail.class))
        .withMessageContaining("@Value");
  }

  /**
   * The substrate resolves {@code @Value} through meta-annotations, so a projection opened that way
   * has to be refused for the expression rather than for its accessor style.
   */
  @Test
  void aMetaAnnotatedValueIsRefusedForTheExpressionRatherThanForTheAccessorStyle() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> anAccountRow().readProjection(ACCOUNT, ShoutedByMetaAnnotation.class))
        .withMessageContaining("@Value")
        .withMessageContaining("getLoud()");
  }

  @Test
  void askingForTheEntityTypeAsAProjectionPointsBackAtRead() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> anAccountRow().readProjection(ACCOUNT, Account.class))
        .withMessageContaining("read(");
  }

  interface MisspelledEmail {
    String getOwnerEmial();
  }

  record SummaryWithAStaleName(Long id, String ownerEmial) {
  }

  /**
   * A projection has no compile-time link to the entity, so renaming a property leaves every
   * projection naming the old one compiling and answering null on every row.
   */
  @Test
  void aProjectionNamingAPropertyTheEntityDoesNotHaveIsRefused() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> anAccountRow().readProjection(ACCOUNT, MisspelledEmail.class))
        .withMessageContaining("ownerEmial")
        .withMessageContaining("Account");
  }

  /**
   * The boundary of the accessor check: only interface projections are checked, so a stale DTO
   * component reads null here exactly as it does through the substrate.
   */
  @Test
  void aStaleComponentOfARecordReadsNullRatherThanBeingRefused() {
    SummaryWithAStaleName projected =
        anAccountRow().readProjection(ACCOUNT, SummaryWithAStaleName.class);

    assertThat(projected.id()).isEqualTo(7L);
    assertThat(projected.ownerEmial()).isNull();
  }

  public static class EmailWithDerivedDomain {

    // The substrate writes this field reflectively, so it reads as never assigned.
    private String ownerEmail;

    public String getOwnerEmail() {
      return ownerEmail;
    }

    public String getDomain() {
      return ownerEmail.substring(ownerEmail.indexOf('@') + 1);
    }
  }

  /**
   * Only what the projection populates has to resolve against the entity; a getter computed from
   * those properties is ordinary Java.
   */
  @Test
  void aDtoMayComputeAValueFromThePropertiesItReads() {
    EmailWithDerivedDomain projected =
        anAccountRow().readProjection(ACCOUNT, EmailWithDerivedDomain.class);

    assertThat(projected.getOwnerEmail()).isEqualTo("owner@example.com");
    assertThat(projected.getDomain()).isEqualTo("example.com");
  }

  interface OwnerId {
    Long getId();
  }

  /**
   * Keyed on the result type alone, the second entity would read through the first's descriptor:
   * {@code getId()} would hand back the foreign key instead of the membership's own.
   */
  @Test
  void oneProjectionInterfaceReadsEachEntitysOwnIdThroughASharedResolver() {
    EntityRef<Membership> membership = EntityRef.of(Membership.class);
    MappingR2dbcConverter converter = new MappingR2dbcConverter(MAPPING_CONTEXT);
    ProjectionResolver shared = new ProjectionResolver(converter);

    ProjectedRow accountRow =
        new ProjectedRow(
            columns("account__account_id", 7L),
            converter,
            StatementAliases.declared(List.of(ACCOUNT)),
            shared);

    ProjectedRow membershipRow =
        new ProjectedRow(
            columns("membership__membership_id", 100L, "membership__account_id", 5L),
            converter,
            StatementAliases.declared(List.of(membership)),
            shared);

    assertThat(accountRow.readProjection(ACCOUNT, OwnerId.class).getId()).isEqualTo(7L);
    assertThat(membershipRow.readProjection(membership, OwnerId.class).getId()).isEqualTo(100L);
  }

  @Test
  void projectionsOfDifferentShapesAndInstancesDoNotBorrowEachOthersDescriptors() {
    ProjectedRow row = anOwnerAndSponsorRow();

    assertThat(row.readProjection(ACCOUNT, OwnerEmail.class).getOwnerEmail())
        .isEqualTo("first@example.com");
    assertThat(row.readProjection(ACCOUNT, OwnerId.class).getId()).isEqualTo(1L);

    assertThat(row.readProjection(SPONSOR, OwnerEmail.class).getOwnerEmail())
        .isEqualTo("second@example.com");
    assertThat(row.readProjection(SPONSOR, OwnerId.class).getId()).isEqualTo(2L);
  }

  interface RecordStyleAccessor {
    String ownerEmail();
  }

  /**
   * Blaming a {@code @Value} expression that is not in the source sends the reader grepping for
   * one that was never written.
   */
  @Test
  void anInterfaceWithNoJavaBeanAccessorIsRefusedForThatRatherThanForAnExpression() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> anAccountRow().readProjection(ACCOUNT, RecordStyleAccessor.class))
        .withMessageContaining("getOwnerEmail()")
        .satisfies(refusal -> assertThat(refusal.getMessage()).doesNotContain("@Value"));
  }

  public static class StaleCountDto {

    public Long id;

    public long visitCount;
  }

  /**
   * A stale primitive field binds the type's default and reads as a real zero, while a stale
   * primitive constructor parameter fails: the difference is field versus constructor binding.
   */
  @Test
  void aStalePrimitiveFieldTakesTheTypesDefaultRatherThanFailing() {
    StaleCountDto projected = anAccountRow().readProjection(ACCOUNT, StaleCountDto.class);

    assertThat(projected.id).isEqualTo(7L);
    assertThat(projected.visitCount).isZero();
  }

  public static class RenamedByColumn {

    // The substrate writes this field reflectively, so it reads as never assigned.
    @Column("owner_email")
    private String email;

    public String getEmail() {
      return email;
    }
  }

  public static class PublicFieldSummary {

    public Long id;

    public String ownerEmail;
  }

  @Test
  void aDtoFieldMayCarryItsOwnColumnName() {
    RenamedByColumn projected = anAccountRow().readProjection(ACCOUNT, RenamedByColumn.class);

    assertThat(projected.getEmail()).isEqualTo("owner@example.com");
  }

  @Test
  void aDtoMayBindStraightToPublicFields() {
    PublicFieldSummary projected = anAccountRow().readProjection(ACCOUNT, PublicFieldSummary.class);

    assertThat(projected.id).isEqualTo(7L);
    assertThat(projected.ownerEmail).isEqualTo("owner@example.com");
  }
}
