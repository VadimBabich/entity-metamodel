# Query recipes

The answers this library gives in place of a feature. The SQL each one renders is pinned by
`DocumentedRecipeRenderingTest`, `PageableTranslationTest` and `SortAndPaginationRenderingTest`, and
the keyset traversals — including the mixed-direction form — are walked against PostgreSQL in the
integration suite, so a published recipe cannot quietly stop working.

`byOwner` below is the description built in the [README](../README.md#five-minutes).

**Is there another page, without counting.** Where you are fetching the page anyway, `slice` is the
answer — it asks for one row beyond the page and issues no count statement at all:

```java
Mono<Slice<Membership>> slice = executor.slice(byOwner, PageRequest.of(0, 20));
```

Where you are *not* fetching the page — a "load more" control that only needs enabling — the probe
is cheaper still, because the description's own offset is honoured. The offset is the end of the
page you are showing, so for the first page of twenty it is twenty:

```java
Mono<Boolean> more = executor.exists(byOwner.offset(20));
```

**Keyset (cursor) pagination.** There is no keyset terminal, and both predicate forms are
expressible today with no library change. The metamodel supplies the qualified column names either
way, so neither spells out a table alias, and neither carries an injection exposure as long as the
template stays a literal: choose between the two templates below with a branch on the sort's
direction, and never assemble one from request data. The bind values are the only part a caller
supplies.

Prefer the row-value form wherever the sort runs one direction throughout — it is the only one of
the two that PostgreSQL turns into an index seek. **The comparison inverts with the sort:** `>`
walks an all-ascending sort, `<` an all-descending one.

```java
// ORDER BY owner_email ASC, account_id ASC
Condition after =
    SqlExpr.raw("({0}, {1}) > ({2}, {3})", Account__.OWNER_EMAIL, Account__.ID,
        cursorEmail, cursorId);

// ORDER BY owner_email DESC, account_id DESC — the feed case
Condition after =
    SqlExpr.raw("({0}, {1}) < ({2}, {3})", Account__.OWNER_EMAIL, Account__.ID,
        cursorEmail, cursorId);
```

Getting that operator wrong is silent: the SQL is valid, the plan still seeks, and the traversal
re-serves the leading page for ever.

That preference is PostgreSQL's, and the row-value form is not portable. **Oracle** refuses it
outright (`ORA-01796`: lists compare only for equivalence), and **SQL Server** has no row-value
comparison at all. **MySQL** accepts it but does not seek on it, as of 8.4: its range optimizer
handles row constructors only under `IN()` (still so in the 9.7 manual), and a verified bug report
(MySQL Bug #111952, reproduced on 8.4.8) shows `(p, i) > (?, ?)` on a primary key running as a full
index scan where the expanded form runs as a range scan — so on MySQL the expanded form is the
indexed one, not the fallback. A decomposition patch was contributed upstream in 2024 (Bug #108116)
and has not been released; on a newer MySQL, `EXPLAIN` the row-value form before trusting this
paragraph. On those three dialects the expanded form below is the answer for every sort, mixed or
not, and the PostgreSQL measurement below does not describe them. Only PostgreSQL is exercised by
this repository's suite.

`SqlExpr.raw` takes `Object...`, so unlike the expanded form a cursor of the wrong type compiles and
fails only at execution. A small generic helper restores the check with no library change — the
shape this repository's own tests use:

```java
static <E, L, T> Condition rowValueAfter(
    PropertyRef<E, L> leadingKey, L leadingCursor, PropertyRef<E, T> terminalKey, T terminalCursor) {
  return SqlExpr.raw(
      "({0}, {1}) > ({2}, {3})", leadingKey, terminalKey, leadingCursor, terminalCursor);
}
```

The expanded form is the fallback for a mixed-direction sort, which the row-value form cannot
express. It is correct and portable, and on PostgreSQL it is **not** free:

```java
Condition after = Account__.OWNER_EMAIL.gt(cursorEmail)
    .or(Account__.OWNER_EMAIL.is(cursorEmail).and(Account__.ID.gt(cursorId)));
```

For a mixed-direction sort each key takes the operator of its own direction — the leading key's
follows its sort, the tie-breaker's follows its own. Standing after (`cursorEmail`, `cursorId`) under
`ORDER BY owner_email DESC, account_id ASC`:

```java
Condition after = Account__.OWNER_EMAIL.lt(cursorEmail)
    .or(Account__.OWNER_EMAIL.is(cursorEmail).and(Account__.ID.gt(cursorId)));
```

Both parenthesise correctly with a filter of your own on either side, and both traverse correctly —
an integration test walks a tied leading key with each.

One `EXPLAIN (ANALYZE, BUFFERS)` per form, on synthetic data you can rebuild: PostgreSQL 16, 200,000
rows, ten rows per distinct leading key so that key is non-unique, a composite index on the two key
columns, `ANALYZE`, page size 20, cursor at the 90th percentile **of the index order**.

| | row-value | expanded |
|---|---|---|
| how the planner uses the predicate | `Index Cond` — seeks to the cursor | `Filter` — scans from the start and discards |
| rows discarded to fill one page | 0 | 180,001 |
| buffers | 5 | 6,190 |
| execution | 0.05 ms | 14.47 ms |

The gap grows with depth — a sweep over the same data timed the expanded form at 1.9 / 9.3 / 16.1 ms
at the 10th, 50th and 90th percentile, while the row-value form stayed flat at ~0.03 ms. **On
PostgreSQL the expanded form uses the index for ordering only, so it reproduces the deep-page
degradation keyset pagination exists to remove.** That is PostgreSQL's planner, not a property of the
form: MySQL ranges on the expanded form and scans on the row-value one. The timings are
point-in-time and the build does not re-check them; the plan shapes are the durable part. On
PostgreSQL, if your sort is mixed-direction and your pages go deep, reversing the whole sort to make
it uniform — so the row-value form applies — is usually the better trade.

What the library cannot check here, and you must:

- **The sort has to end in a unique key** — the entity's `@Id` is the usual one. Without it the
  traversal skips or repeats rows, and no error says so.
- **Every sort property must be projected**, and the key values must match the sort in arity, order
  and type.
- **No null sort keys.** Both forms evaluate to NULL where a key is NULL, so those rows drop out of
  the traversal silently.
- **The row-value form needs one direction throughout, and its operator has to match that
  direction.** A mixed-direction sort such as `createdAt DESC, id ASC` is what neither operator can
  express, so that one needs the expanded form — at the cost measured above.
- **Not over a to-many join,** where one root id no longer identifies one row. Add `distinct()` and
  the joined instance's id to the key, or key on a description that does not join.

**What fails at the server rather than here.** An `IN` list past the driver's parameter ceiling fails as a
reported driver error with the connection intact, not silently — observed on PostgreSQL at its ceiling
of 65 535 binds; MySQL documents the same ceiling and SQL Server 2 100, where the same failure shape is
expected but not exercised by this repository's suite. The library caps no list. A raw `SqlExpr` fragment whose text
exceeds what the server's parser accepts fails the same way, as a reported syntax error with the
connection intact; the library's depth limit does not apply to fragment text, because the fragment is
not parsed here.
