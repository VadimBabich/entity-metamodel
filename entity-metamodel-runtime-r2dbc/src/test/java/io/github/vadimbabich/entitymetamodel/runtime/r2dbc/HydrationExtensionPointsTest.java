package io.github.vadimbabich.entitymetamodel.runtime.r2dbc;

import static io.github.vadimbabich.entitymetamodel.runtime.r2dbc.ProductionPatterns.ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.Account;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures.AccountState;
import io.r2dbc.spi.Row;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.data.relational.domain.RowDocument;

/**
 * The extension points {@link ProjectedRow} documents, pinned — because a consumer following
 * Spring's own guidance can register a hook here that never runs, and nothing would say so.
 *
 * <p>Rows are read with {@code converter.read(type, RowDocument)} rather than through an
 * {@code R2dbcEntityTemplate}, which decides what fires. Two of these facts are registration
 * behaviour in the substrate rather than our own code, so only a test keeps the documentation
 * honest across a dependency upgrade.
 */
class HydrationExtensionPointsTest {

  private static ProjectedRow accountRow(MappingR2dbcConverter converter) {
    Map<String, Object> projectedColumns = new LinkedHashMap<>();
    projectedColumns.put("account__account_id", 7L);
    projectedColumns.put("account__owner_email", "owner@example.com");
    projectedColumns.put("account__state", "ACTIVE");

    return new ProjectedRow(
        projectedColumns,
        converter,
        StatementAliases.declared(List.of(ACCOUNT)),
        new ProjectionResolver(converter));
  }

  private static MappingR2dbcConverter converterWith(Object... customConverters) {
    R2dbcMappingContext mappingContext = new R2dbcMappingContext();
    R2dbcCustomConversions conversions =
        R2dbcCustomConversions.of(PostgresDialect.INSTANCE, List.of(customConverters));

    mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());

    return new MappingR2dbcConverter(mappingContext, conversions);
  }

  @NullMarked
  @ReadingConverter
  private static final class RowToAccount implements Converter<Row, Account> {

    private boolean fired;

    @Override
    public Account convert(Row source) {
      fired = true;
      return new Account();
    }
  }

  @NullMarked
  @ReadingConverter
  private static final class AnnotatedDocumentToAccount implements Converter<RowDocument, Account> {

    private boolean fired;

    @Override
    public Account convert(RowDocument source) {
      fired = true;
      return new Account();
    }
  }

  @NullMarked
  private static final class UnannotatedDocumentToAccount
      implements Converter<RowDocument, Account> {

    private boolean fired;

    @Override
    public Account convert(RowDocument source) {
      fired = true;
      return new Account();
    }
  }

  @NullMarked
  @ReadingConverter
  private static final class TextToState implements Converter<String, AccountState> {

    private boolean fired;

    @Override
    public AccountState convert(String source) {
      fired = true;
      return AccountState.CLOSED;
    }
  }

  /**
   * The hook Spring's reference documentation teaches for entity-level reads. It is consulted only
   * when a {@code Row} is the source, and this path never holds one.
   */
  @Test
  void anEntityLevelRowConverterNeverFiresAndTheEntityHydratesNormally() {
    RowToAccount rowConverter = new RowToAccount();

    Account hydrated = accountRow(converterWith(rowConverter)).read(ACCOUNT);

    assertThat(rowConverter.fired).isFalse();
    assertThat(hydrated.id).isEqualTo(7L);
  }

  /**
   * The working alternative to the {@code Row} hook, with or without the annotation —
   * {@code RowDocument} is a {@code Map}, which the store counts as a simple source type, so an
   * unannotated converter still registers for reading.
   */
  @Test
  void anEntityLevelDocumentConverterFiresWhetherOrNotItIsAnnotated() {
    AnnotatedDocumentToAccount annotated = new AnnotatedDocumentToAccount();
    UnannotatedDocumentToAccount unannotated = new UnannotatedDocumentToAccount();

    accountRow(converterWith(annotated)).read(ACCOUNT);
    accountRow(converterWith(unannotated)).read(ACCOUNT);

    assertThat(annotated.fired).isTrue();
    assertThat(unannotated.fired).isTrue();
  }

  @Test
  void aPropertyLevelReadingConverterFires() {
    TextToState stateConverter = new TextToState();

    Account hydrated = accountRow(converterWith(stateConverter)).read(ACCOUNT);

    assertThat(stateConverter.fired).isTrue();
    assertThat(hydrated.state).isEqualTo(AccountState.CLOSED);
  }

  @NullMarked
  @ReadingConverter
  private static final class RejectingTextToState implements Converter<String, AccountState> {

    @Override
    public AccountState convert(String source) {
      throw new IllegalArgumentException("no state named " + source);
    }
  }

  interface EmailOnly {
    String getOwnerEmail();
  }

  record EmailOnlyDto(String ownerEmail) {
  }

  /**
   * The two projection forms are not interchangeable: an interface projection reads and converts
   * every property of the entity and narrows only the object handed back, while a DTO reads the
   * properties it declares. Swapping one for the other therefore changes which columns are
   * converted, and a column whose converter rejects the stored value fails a listing that never
   * mentions it.
   */
  @Test
  void anInterfaceProjectionConvertsEveryPropertyWhileADtoReadsOnlyItsOwn() {
    MappingR2dbcConverter converter = converterWith(new RejectingTextToState());

    assertThatExceptionOfType(ConversionFailedException.class)
        .isThrownBy(() -> accountRow(converter).readProjection(ACCOUNT, EmailOnly.class));

    EmailOnlyDto narrowed = accountRow(converter).readProjection(ACCOUNT, EmailOnlyDto.class);

    assertThat(narrowed.ownerEmail()).isEqualTo("owner@example.com");
  }

  interface EmailAndState {
    String getOwnerEmail();

    AccountState getState();
  }

  @Test
  void aProjectionAppliesPropertyLevelConverters() {
    TextToState propertyConverter = new TextToState();

    EmailAndState projected =
        accountRow(converterWith(propertyConverter)).readProjection(ACCOUNT, EmailAndState.class);

    assertThat(propertyConverter.fired).isTrue();
    assertThat(projected.getState()).isEqualTo(AccountState.CLOSED);
  }

  /**
   * The projection path reads properties directly and cannot consult an entity-level converter, so
   * the two doors would otherwise disagree about the same row — the whole entity rewritten by the
   * converter, the projection handing back the stored value. Where that converter redacts or
   * decrypts a column, the quiet door is the leaking one.
   */
  @Test
  void aProjectionIsRefusedWhereAnEntityLevelConverterWouldBeBypassed() {
    MappingR2dbcConverter converter = converterWith(new AnnotatedDocumentToAccount());

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> accountRow(converter).readProjection(ACCOUNT, EmailAndState.class))
        .withMessageContaining("Account")
        .withMessageContaining("read(");
  }
}
