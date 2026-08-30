# Reactive Code Style

Rules for reactive code in this repository: the hand-written reactive runtime, the reactive code the
generator **emits**, and the tests for both. It is the reactive counterpart to the general code style in
`CLAUDE.md` — that document's rules (explicit control flow, domain names, no `null`, constructor
injection, comments explain *why*) apply here unchanged; this one adds what Reactive Streams makes
possible to get wrong.

Rules derived from a study of production Spring Data R2DBC services, i.e. from how this library is
actually consumed — not from preference. Where a rule reverses common practice, the reason is stated.

**Applies to:** `entity-metamodel-runtime`, `entity-metamodel-runtime-r2dbc`, every reactive line the
processor emits, and their tests.

**Two audiences, one rule set.** A rule tagged **[L]** binds *library* code — ours, hand-written or
generated, running inside somebody else's application. A rule tagged **[C]** is what we teach
*consumers* and follow in examples, ITs and docs. Library code is held to the stricter standard because
it cannot see the application's threading, latency budget or transaction boundaries.

---

## 1. Prohibitions — no exceptions without an ADR

1. **Never block.** [L][C] No `block()`, `blockFirst()`, `blockLast()`, `toIterable()`,
   `toStream()`, `Thread.sleep`, no blocking I/O, no synchronous JDBC. A single blocking call on an
   event-loop thread stalls every request that thread is multiplexing. Blocking calls are legal in
   tests only (§9), and even there `StepVerifier` is preferred.
2. **Never choose the caller's threads.** [L] No `subscribeOn`, `publishOn`, `Schedulers.*` in library
   or generated code. The application and the driver own the execution model; a library that hops
   threads breaks context propagation and hides the real cost from whoever tuned the pool. If work
   genuinely must move (it never has yet), that requires an ADR, not a judgement call.
3. **Never subscribe to your own chain.** [L] No `subscribe()`, `subscribe(Consumer)`, or
   `.toFuture()` in library code. Return the publisher; the framework subscribes. A library-side
   subscription is fire-and-forget: it detaches from the caller's lifecycle, escapes cancellation, and
   swallows errors into a dropped-signal handler.
4. **Never embed policy.** [L] No `timeout()`, no `retryWhen()`, no `onErrorContinue()`, no
   `cache()`, no `share()`, no `limitRate()` inside library or generated code. Latency budgets,
   retry semantics and caching are the application's decisions and are wrong more often than right when
   guessed. Return a cold publisher the consumer can decorate.
   *Corollary for consumers:* [C] every call that crosses the network **should** carry an explicit
   `timeout()`, and `retryWhen()` where the operation is idempotent. Say so in the docs; do not do it
   for them.
5. **No `@Transactional`, no transaction management, in library code.** [L] The consumer owns
   transaction boundaries (§7).
6. **No ambient state.** [L] No static mutable fields, no lazily-initialised caches, no
   double-checked locking in runtime or generated types. This is the D3 decision, and it is also a
   correctness rule: publishers are assembled and subscribed on arbitrary threads, so a non-`final`
   field written during assembly and read during rendering is a data race.

## 2. Laziness and assembly discipline

The single most common reactive defect: work performed while *building* the chain rather than when it
is *subscribed*.

**Assembly time** is when operators are wired together. **Subscription time** is when data flows.
Every argument you pass to an operator is evaluated at assembly time, on every call, whether or not
that branch is ever used.

- **A fallback argument that costs anything must be deferred.** [L][C] `switchIfEmpty(...)`,
  `onErrorResume(...)` and `defaultIfEmpty(...)` evaluate their argument eagerly. Constructing an
  exception is not free — filling in a stack trace is one of the more expensive things a JVM does —
  so an error fallback built inline runs on the happy path too:

  ```java
  // WRONG: the exception is constructed on every call, including when the entity is found
  return repository.findById(id)
      .switchIfEmpty(notFound(id));

  // RIGHT: constructed only when the source is actually empty
  return repository.findById(id)
      .switchIfEmpty(Mono.defer(() -> notFound(id)));

  // ALSO RIGHT, and preferred when the fallback is only an error
  return repository.findById(id)
      .switchIfEmpty(Mono.error(() -> new EntityNotFoundException(id)));
  ```

  `Mono.error(Supplier)` and `Mono.defer(Supplier)` are the two correct forms. A fallback that is a
  pre-existing constant (`Mono.empty()`, a cached immutable value) needs no deferral.
- **Never wrap a method call in `Mono.just(...)`.** [L][C] `Mono.just(load())` calls `load()` at
  assembly time — the laziness is an illusion. Use `Mono.fromSupplier(() -> load())`, or
  `Mono.fromCallable` when it throws checked exceptions.
- **A cold publisher re-executes on every subscription.** [L][C] Naming a publisher in a local
  variable does not memoise it. If two branches subscribe to the same named `Mono` that performs I/O,
  the I/O happens twice:

  ```java
  Mono<Invoice> invoice = repository.findById(id);   // one description, not one result

  Mono<Invoice> withCustomer = invoice.flatMap(this::attachCustomer);
  Mono<Invoice> withLines    = invoice.flatMap(this::attachLines);   // second round trip
  ```

  Fix by composing once (`invoice.flatMap(i -> ...)` doing both) or by `zipWhen`/`zip` on a single
  subscription. `.cache()` also fixes it but is banned in library code (§1.4) — in consumer code it is
  the right tool, with a considered TTL.
- **Validate cheaply at assembly, fail properly at subscription.** [L] Programmer errors — a `null`
  `PropertyRef`, a property that does not belong to the entity, an unresolvable join — are argument
  errors: throw `NullPointerException`/`IllegalArgumentException` **immediately** from the builder
  method, so the stack trace points at the offending line. Anything that depends on data or on the
  connection must be signalled as `onError` inside the returned publisher, never thrown. Do not mix
  the two: a builder that sometimes throws and sometimes returns a failed `Mono` for the same class of
  problem is untestable.

## 3. Composition and readability

The `CLAUDE.md` rule — *explicit control flow over clever compression* — applies to operator chains,
and reactive code makes it easier to violate.

- **A chain reads as one idea or it becomes named methods.** [L][C] `flatMap` nested inside `flatMap`
  inside `flatMap` is the callback pyramid the reactive style was supposed to remove. Two levels is a
  smell; three is a defect. Extract each level into a named private method taking the value it needs
  and returning a publisher.
- **Name intermediate publishers for what they *are*.** [L][C] `persistedInvoice`, `resolvedJoin` —
  not `mono1`, `result`, `data`. Never name a variable after its type (`invoiceMono` says nothing that
  the type does not); name it after the value it will carry.
- **Never name a method after a negation or a mechanism.** [C] `doThisIfConditionFalse` cannot be
  read at the call site, and a name that promises one operator while using another is worse than no
  name. Positive domain names, always.
- **No conditional embedded in a return expression** — including when the branches are publishers.
  Reactor makes `cond ? Mono.error(...) : Mono.just(x)` compile; the house style still forbids it. Use
  an `if` with an early return, or `filter(...).switchIfEmpty(...)` where the shape genuinely is
  "absent → fail".
- **Bound the concurrency of `flatMap` over an unbounded source.** [L][C] `flatMap` on a `Flux`
  subscribes to up to 256 inner publishers by default. Over a result set of unknown size, against a
  connection pool of a dozen connections, that is pool exhaustion and unfair queuing. Pass the
  concurrency explicitly — `flatMap(this::load, 8)` — sized against the pool, not the data.
- **Pick the operator that states the ordering you need.** [L][C] `flatMap` interleaves and does not
  preserve order; `concatMap` preserves order with one inner subscription at a time;
  `flatMapSequential` runs eagerly but emits in order. If order matters, saying so with the operator is
  better than a comment. Reaching for `flatMap` reflexively, then sorting downstream, is not.
- **`then`, `thenReturn`, `thenMany` for sequencing, not `flatMap`.** [L][C] When the previous value
  is irrelevant and only completion matters, these say so and drop the value; `flatMap(x -> ...)` that
  ignores `x` does not.

## 4. Absence, errors and cancellation

- **Empty is a value, not an error — until a boundary decides otherwise.** [L] Runtime and generated
  code return empty publishers for "no rows"; they do not invent `EntityNotFoundException`. Turning
  absence into an error is the application's policy, expressed at its own boundary.
- **The consumer-side idiom for absence is `switchIfEmpty` into a deferred error** (§2), and for a
  rule violation `filter(...).switchIfEmpty(...)`. [C] Keep the error factories as small named methods
  returning `Mono<T>`; keep the message free of data the caller is not allowed to see.
- **Signal failures as typed exceptions inside the publisher.** [L] One exception type per failure
  mode, each carrying the identifiers needed to act on it. Never signal a raw `RuntimeException`, never
  a `String` message as the only payload.
- **Translate substrate and driver failures at the edge of our code.** [L] Where we own the call into
  the driver, unwrap the cause and map a recognised condition (a constraint violation, a serialisation
  failure) onto our own exception type with `onErrorMap`. Do not let a driver type escape through a
  signature the consumer sees, and do not swallow the cause — always chain it.
- **Narrow error hooks only.** [L][C] `doOnError(SpecificException.class, ...)` and
  `onErrorResume(SpecificException.class, ...)` over catch-all forms. `onErrorContinue` is banned
  outright: it depends on upstream operators cooperating, silently changes semantics, and is
  unimplementable in a library that cannot see the whole chain.
- **`doOn*` hooks are for observation only.** [L][C] Logging, metrics, tracing. No mutation of shared
  state, no I/O, nothing whose failure would matter — an exception thrown from a `doOn*` callback
  becomes a fatal signal-delivery error, not a normal `onError`.
- **Cancellation must propagate.** [L] Never swallow cancellation to "finish the work anyway". If a
  resource is acquired, release it with `using`/`usingWhen` or `doFinally`, and make the release
  handle all three terminations (complete, error, cancel). A `doFinally` that only handles
  `SignalType.ON_COMPLETE` leaks on cancellation, which is the common case when a client disconnects.

## 5. Boundaries: what shape crosses the API

- **Return `Mono<T>` for one-or-none, `Flux<T>` for a stream.** [L] Never `Publisher<T>` in a public
  signature — it denies the consumer every operator. Never `Mono<Optional<T>>`, never
  `Mono<List<T>>` where the count is unbounded, never `Flux<T>` for something that yields exactly one
  value.
- **Provide both the streaming and the collected terminal.** [L] Production call sites overwhelmingly
  want a bounded page — a collected list plus a total — and only rarely a true stream. The fluent API
  therefore offers a `Flux<T>` terminal *and* collected terminals; it does not force consumers to
  `collectList()` a stream they never wanted to stream.
- **Do not stream unbounded result sets to a client by default.** [C] Pagination or an explicit
  limit at the query, not a `Flux` straight to the wire. Streaming is a deliberate choice for a
  genuinely large or long-lived response, and it changes error handling: once the first element is
  written, an error can no longer become a clean HTTP status.
- **Bounded results are assembled reactively, never blockingly.** [C] Collect the page and the count
  in one composition (`collectList().zipWith(count, ...)`); do not block on either.
- **Every publisher-returning method must be idempotent in assembly.** [L] Calling it twice must
  produce two independent, equivalent descriptions with no shared mutable state between them, and
  calling it without subscribing must have no effect at all.
- **Builders are immutable.** [L] Each fluent step returns a new instance; no step mutates the
  receiver. A shared half-built query must be safe to hand to two threads that each finish it
  differently.

## 6. Context and ambient state

- **Pass what the operation needs as parameters.** [L][C] The tenant, the principal, the clock — an
  explicit parameter is greppable, testable and impossible to forget. Reading them from ambient state
  is what makes reactive code untestable, and thread-local ambient state does not survive the first
  thread hop.
- **Library code must never *require* a Reactor context entry.** [L] The runtime must function with
  an empty context. Reading an optional context entry to *optimise* (skip a lookup because an upstream
  step already resolved the value) is legitimate; failing without one is not.
- **Context keys are class tokens or dedicated key objects, never string literals.** [L][C] A
  `String` key is a collision waiting to happen and forces an unchecked cast at every read; a class
  token carries the type. Read with `deferContextual` + `getOrEmpty`, and always have a fallback path.
- **Never use the Reactor context as a general-purpose parameter bag.** [C] It is for cross-cutting,
  request-scoped values that would otherwise thread through every signature. Domain arguments belong
  in the signature.

## 7. Transactions

- **The consumer owns the boundary; the library joins it.** [L] Runtime and generated code carry no
  `@Transactional`, never start or commit a transaction, and never assume one exists. They execute on
  whatever connection the ambient transactional context supplies.
- **Reactive transactions propagate through the Reactor context, not thread-locals.** [L][C] The
  consequences: work must stay inside the returned publisher's composition to be inside the
  transaction; anything scheduled elsewhere, or reached via a blocking bridge, is outside it; and a
  `@Transactional` method whose returned publisher is never subscribed does nothing at all.
- **Mark read paths `readOnly = true`.** [C] It keeps read work off write connections and lets the
  driver and database optimise. A service annotated once at class level with everything defaulting to
  read-write is the common, costly default.
- **Post-commit work uses the reactive synchronisation API.** [C] Register a synchronisation on the
  current reactive transaction and honour the read-only flag it passes; a callback that writes during a
  read-only commit is a bug.
- **Concurrency conflicts are detected, not assumed away.** [C] Where a version column guards an
  update, compare-and-set in the statement and treat an affected-row count other than the expected one
  as a conflict error, with a message that says the operation may be retried. Silent zero-row updates
  are the defect this prevents.

## 8. Generated reactive code

Everything in §§1–7 binds generated code, which cannot be reviewed line by line at every consumer.
Additionally:

- **Generated code performs no I/O and holds no state.** Metamodel types are immutable value
  descriptions: `static final` fields, `final` instance fields assigned in the constructor, no lazy
  initialisation, no registry, no cache. They must be safe to publish to any thread and to use from
  many concurrently.
- **Generated code never mentions a scheduler, a timeout, a retry, or a transaction.** It offers no
  policy and no side channel.
- **Generated code emits no blocking call and no `subscribe`.** Not even in a `toString`, a
  diagnostic, or a commented-out line — generated files are copied by consumers into their own code
  more often than we would like.
- **The generator is the enforcement point for §2.** Emitted fallbacks are deferred; emitted
  publishers are cold; no emitted expression performs work at assembly time.
- **Determinism outranks elegance.** Emission order is fixed by the generator's own rules, never by
  reflection order, hash order, or annotation-processing round order. This is a release rail, not a
  style preference: generated output is byte-compared in CI.
- **Generated code depends only on artefacts we own plus the declared substrate surface.** No
  reference to a substrate internal package, and no type in a generated signature that the consumer
  cannot name from their own dependencies.
- **Rendering rules for anything that produces SQL:**
  - Identifiers come from the model — never a literal string, never string-concatenated user input.
  - Values are bound as **named parameters**; a value never reaches the statement text.
  - Statement text is assembled once per description and reused across subscriptions, not rebuilt per
    subscription.
  - The rendered statement is a pure function of the description: same builder, same SQL, same
    parameter order, every time.

## 9. Tests

- **`StepVerifier` is the house idiom.** [L][C] Assert on signals — `expectNext`, `expectComplete`,
  `expectError(Type.class)`, `expectNextCount`, `thenCancel().verify()`. `block()` in a test collapses
  a stream to one value and asserts nothing about completion, ordering, error type, cancellation or
  backpressure, which is exactly where reactive defects live.
- **`block()` is allowed only in fixtures and setup**, where the value is a precondition rather than
  the thing under test. It must never be the assertion.
- **Every publisher-returning method has a cancellation test and an error-path test.** These are the
  two behaviours that hand-written chains get wrong and that no unit test of the happy path detects.
- **Time is virtual.** `StepVerifier.withVirtualTime` for anything involving delay or timeout. No
  `Thread.sleep`, no wall-clock waits, no `Awaitility` polling for a reactive condition.
- **Assert that library code did *not* block.** Where the runtime is exercised end to end, run the
  suite with a blocking-call detector installed so an accidental blocking call fails the build instead
  of degrading production silently.
- **Generated-code tests assert bytes, not behaviour alone.** Golden-corpus comparison stays the
  acceptance criterion for emission; behavioural tests run against the compiled output.

## 10. Enforcement

Reviews miss these; make the build catch them.

1. **Architecture tests** (the same ArchUnit suite that enforces the API/SPI rules) assert, over the
   runtime and generated-output modules: no reference to `block`/`blockFirst`/`blockLast`/`toIterable`/
   `toStream`, no reference to `Schedulers` or `subscribeOn`/`publishOn`, no `subscribe`, no
   `onErrorContinue`, no `@Transactional`, no non-`final` static field.
2. **Blocking-call detection** installed in the reactive test suite (§9).
3. **IDE inspections are part of the quality gate** (`CLAUDE.md` § Build, test and quality gate).
   Reactor Netty/Reactive inspections — "blocking call in non-blocking context", "subscribe called in
   a reactive chain", "publisher never subscribed" — are errors, not warnings, and are fixed at the
   cause rather than suppressed.
4. **Review checklist for any diff touching a reactive chain:** is every fallback deferred? is any
   named publisher subscribed twice? is `flatMap` concurrency bounded? does the operator state the
   ordering the caller needs? is cancellation handled in the cleanup? is there a `StepVerifier` test
   for the error and cancellation paths?

---

**Amending this document.** Rules 1.1–1.6 and §8 are load-bearing for the library's contract; changing
one requires an ADR, cited by filename in the commit message. Everything else may be refined by a
normal commit that explains why in the message.
