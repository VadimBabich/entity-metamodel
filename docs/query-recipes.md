# Query recipes

The answers this library gives in place of a feature. The SQL each one renders is pinned by
`DocumentedRecipeRenderingTest`, `PageableTranslationTest` and `SortAndPaginationRenderingTest`, and
the keyset traversals are walked against PostgreSQL in the integration suite, so a published recipe
cannot quietly stop working.

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
way, so neither spells out a table alias and neither carries an injection exposure.

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

The expanded form is the fallback for a mixed-direction sort, which the row-value form cannot
express. It is correct and portable, and it is **not** free:

```java
Condition after = Account__.OWNER_EMAIL.gt(cursorEmail)
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
at the 10th, 50th and 90th percentile, while the row-value form stayed flat at ~0.03 ms. **The
expanded form uses the index for ordering only, so it reproduces the deep-page degradation keyset
pagination exists to remove.** The timings are point-in-time and the build does not re-check them;
the plan shapes are the durable part. If your sort is mixed-direction and your pages go deep,
reversing the whole sort to make it uniform is usually the better trade.

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
