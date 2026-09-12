package io.github.vadimbabich.entitymetamodel.runtime;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A raw SQL fragment used as a condition — the escape hatch for what the typed vocabulary cannot
 * express. An owned type, so no substrate expression reaches a signature the fragment touches.
 *
 * <p>The fragment is a <em>template</em>, never a place to put values: {@code {0}} names the first
 * argument, {@code {1}} the second. A {@link PropertyRef} argument becomes that instance's
 * qualified column, anything else a bind marker. An argument may be referenced more than once, and
 * every argument must be referenced at least once.
 *
 * <p><strong>Everything that is not {@code {digits}} is literal text</strong> — including
 * {@code ?}, which PostgreSQL uses as a jsonb operator ({@code ?}, {@code ?|}, {@code ?&}). There
 * is no escape mechanism, and unlike {@code MessageFormat} a {@code '} is just a quote.
 */
public record SqlExpr(String sql, List<Object> arguments) implements Condition {

  // Mirrored by the assembling half in the execution module's renderer. Duplicated rather than
  // shared: sharing would promote a template grammar into public API, and would guard the pattern's
  // text without guarding how each side reads a match. The in-step matrix test guards that.
  private static final Pattern ARGUMENT_REFERENCE = Pattern.compile("\\{([0-9]+)}");

  public SqlExpr {
    Objects.requireNonNull(sql, "sql");
    Objects.requireNonNull(arguments, "arguments");

    if (sql.isBlank()) {
      throw new IllegalArgumentException("A raw SQL fragment must not be blank");
    }

    arguments = detachedArgumentsOf(arguments);
    rejectUnresolvableReferences(sql, arguments.size());
  }

  /**
   * <strong>The caller owns the safety of {@code sql}.</strong> Never assemble it from untrusted
   * input; pass every value as an argument rather than writing it into the fragment.
   *
   * <p>An array argument needs an {@code (Object)} cast — {@code raw(sql, (Object) keys)} — or the
   * compiler spreads it into the varargs and each element becomes its own argument.
   */
  @RawSql
  public static SqlExpr raw(String sql, Object... arguments) {
    Objects.requireNonNull(arguments, "arguments");

    return new SqlExpr(sql, Arrays.asList(arguments));
  }

  private static List<Object> detachedArgumentsOf(List<Object> arguments) {
    List<Object> detached = new ArrayList<>(arguments.size());

    for (int argumentIndex = 0; argumentIndex < arguments.size(); argumentIndex++) {
      Object argument = arguments.get(argumentIndex);

      Objects.requireNonNull(argument, "argument " + argumentIndex + " must not be null - a null"
          + " cannot be bound without a type; write IS NULL into the fragment instead");

      detached.add(copyIfArray(argument));
    }

    return List.copyOf(detached);
  }

  private static Object copyIfArray(Object argument) {
    if (argument instanceof Object[] references) {
      return references.clone();
    }
    if (!argument.getClass().isArray()) {
      return argument;
    }

    int length = Array.getLength(argument);
    Object copy = Array.newInstance(argument.getClass().getComponentType(), length);

    for (int index = 0; index < length; index++) {
      Array.set(copy, index, Array.get(argument, index));
    }

    return copy;
  }

  private static void rejectUnresolvableReferences(String sql, int argumentCount) {
    boolean[] referenced = new boolean[argumentCount];
    Matcher references = ARGUMENT_REFERENCE.matcher(sql);

    while (references.find()) {
      int argumentIndex = referencedArgument(references.group(), references.group(1), sql,
          argumentCount);

      referenced[argumentIndex] = true;
    }

    rejectUnreferencedArgument(referenced, sql);
  }

  private static int referencedArgument(
      String reference, String digits, String sql, int argumentCount) {

    int argumentIndex;
    try {
      argumentIndex = Integer.parseInt(digits);
    } catch (NumberFormatException tooLargeForAnIndex) {
      // Out of range by definition: an enormous index is the same mistake as {5} with two
      // arguments, and needs the same answer rather than a parse failure.
      throw outOfRange(reference, sql, argumentCount);
    }

    if (argumentIndex >= argumentCount) {
      throw outOfRange(reference, sql, argumentCount);
    }

    return argumentIndex;
  }

  private static void rejectUnreferencedArgument(boolean[] referenced, String sql) {
    for (int argumentIndex = 0; argumentIndex < referenced.length; argumentIndex++) {
      if (referenced[argumentIndex]) {
        continue;
      }

      throw new IllegalArgumentException(
          "The fragment was given " + referenced.length + " argument(s) but never references"
              + " argument " + argumentIndex + ": " + sql + ". Write {" + argumentIndex
              + "} where it belongs, or drop it — an argument nothing names is a mistake rather"
              + " than slack, and would otherwise be bound to nothing");
    }
  }

  // Also where a literal that merely looks like a reference lands, which is why the message names
  // that case: a single-element PostgreSQL array literal is spelled exactly like one.
  private static IllegalArgumentException outOfRange(
      String reference, String sql, int argumentCount) {

    return new IllegalArgumentException(
        "The fragment references " + reference + " but was given " + argumentCount
            + " argument(s): " + sql + ". References are 0-based. If " + reference + " was meant as"
            + " literal text — an array literal such as '{5}'::int[] reads exactly like a"
            + " reference — write it as ARRAY[...] instead, or bind the array as an argument,"
            + " because a reference's braces are consumed and a literal one cannot survive here");
  }
}
