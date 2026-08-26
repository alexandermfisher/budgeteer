# Java Generics, One Program at a Time

**Lesson 1: boxes, functions, and `map`** — no wildcards, that's Lesson 2.

We build one tiny program — three files — and every snippet in this lesson belongs to it.
By the end you can paste the final listing into a scratch folder and run it.

---

## Part 0 — Who's who: the language, the JDK, and us

First, a question you asked that untangles a lot: **is `Sourced` an official Java type like `Optional`?**

No — and it's worth being precise about the three layers involved:

| Layer | What it is | Examples |
|---|---|---|
| **The language** | Syntax and rules baked into Java itself | `record`, `interface`, generics (`<T>`), lambdas (`->`) |
| **The JDK standard library** | Ordinary classes/interfaces that *ship with* Java, written using that syntax | `Optional`, `Function`, `List`, `Map`, `String` |
| **Your project** | Code you wrote | `BankTransaction`, `Sourced`, `MonzoAccountInformationProvider` |

`Optional` feels "official" but it isn't language magic — it's a normal class in `java.util`
that somebody at Oracle wrote, using the same tools you have. `Sourced` is a class **we are
inventing in this lesson** (and for Budgeteer's `provider-api`). It doesn't exist anywhere
else. That's the point of the lesson: the JDK's fancy types are buildable by you.

> **Clearing up my earlier mess:** in the previous lesson I pasted `Optional.map`'s actual
> JDK source code as a comparison, mid-lesson, without flagging it. So no — we were never
> *using* an Optional. It was the JDK's own code for *their* box, shown next to *our* box.
> In this lesson we don't touch `Optional` until the very end, and only to point at it.

---

## Part 1 — The program and its problem

Our program models one thing Budgeteer really does: a provider parses bank-API JSON into a
neat domain object, but we must also keep the **verbatim raw JSON** it came from (for
auditing and future re-parsing — "provenance").

File one — a simplified version of the real record:

```java
// ── file: BankTransaction.java ──────────────────────────────
public record BankTransaction(
        String externalId,
        long amountMinorUnits,
        String merchantName
) { }
```

File two — a main class where a pretend "parse" has just happened:

```java
// ── file: Main.java (version 1) ─────────────────────────────
public class Main {
    public static void main(String[] args) {
        // In the real app this JSON arrives from Monzo and Jackson parses it.
        // Here we fake both steps so the program is self-contained:
        String rawJson = "{\"id\":\"tx_001\",\"amount\":-450,\"merchant\":\"Pret A Manger\"}";
        BankTransaction tx = new BankTransaction("tx_001", -450, "Pret A Manger");

        // PROBLEM: tx and rawJson are two loose variables. Nothing ties them
        // together. Pass tx to another method and the raw JSON is left behind.
    }
}
```

We want to hand these around **as one unit**. First attempt — a purpose-built pair class:

```java
// A non-generic first attempt (we will delete this)
public record SourcedTransaction(BankTransaction payload, String rawJson) { }
```

Works! But Budgeteer also parses accounts, balances, tokens… so next we'd write
`SourcedAccount`, `SourcedBalance`, `SourcedTokens` — four copies of the same idea where
only **one type name differs**. Whenever the *only* thing varying between copies of a class
is a type, that is precisely the itch generics scratch.

---

## Part 2 — The generic box: `Sourced<T>`

Replace the copies with one class that has a **placeholder** where the varying type was:

```java
// ── file: Sourced.java (version 1) ──────────────────────────
public record Sourced<T>(T payload, String rawJson) { }
```

`T` is not a real type. It's a blank that each *use site* fills in. When `Main` writes
`Sourced<BankTransaction>`, the compiler treats the record, for that use, as if it were:

```java
// what the compiler "sees" for Sourced<BankTransaction> — never actually written
public record Sourced(BankTransaction payload, String rawJson) { }
```

Update `Main` to use it — note the same box working for two different payload types:

```java
// ── file: Main.java (version 2) ─────────────────────────────
public class Main {
    public static void main(String[] args) {
        String rawJson = "{\"id\":\"tx_001\",\"amount\":-450,\"merchant\":\"Pret A Manger\"}";
        BankTransaction tx = new BankTransaction("tx_001", -450, "Pret A Manger");

        Sourced<BankTransaction> sourced = new Sourced<>(tx, rawJson);
        //      ^^^^^^^^^^^^^^^ we fill the blank: T = BankTransaction here

        BankTransaction back = sourced.payload();   // typed! no cast needed
        System.out.println(back.merchantName());    // Pret A Manger
        System.out.println(sourced.rawJson());      // the verbatim JSON

        // Same class, completely different payload type — this is the reuse win:
        Sourced<String> greeting = new Sourced<>("hello", "{\"greeting\":\"hello\"}");
        String s = greeting.payload();              // typed as String
    }
}
```

Vocabulary worth having: `T` in the declaration is the **type parameter**; the
`BankTransaction` filling it at a use site is the **type argument**. The `<>` in
`new Sourced<>(tx, rawJson)` ("diamond") just means "compiler, infer the argument
from context" — it saves writing `new Sourced<BankTransaction>(...)`.

---

## Part 3 — Detour that pays off: what `.of` factories are

You asked about `Optional.of(x)` and `Map.of(...)`. A **static factory method** is nothing
more than a `static` method that constructs and returns an instance — an alternative front
door to `new`. Let's add one to *our* class so it's demystified:

```java
// ── file: Sourced.java (version 2) ──────────────────────────
import java.util.Objects;

public record Sourced<T>(T payload, String rawJson) {

    /** Static factory — same job as `new`, with validation and a readable name. */
    public static <T> Sourced<T> of(T payload, String rawJson) {
        Objects.requireNonNull(payload, "payload must not be null");
        return new Sourced<>(payload, rawJson);
    }
}
```

Now `Main` can say `Sourced.of(tx, rawJson)` instead of `new Sourced<>(tx, rawJson)`.
Why do factories exist at all, if `new` works?

1. **They can validate or reject** before constructing (our null check).
2. **They can return a hidden implementation.** `List.of(1, 2)` doesn't return an
   `ArrayList` — it returns some private immutable class you never see. `new` can't do
   that; a factory can return any subtype it likes.
3. **They read well:** `Optional.of(x)`, `Map.of("k", "v")`, `Duration.ofDays(350)`.

So when you see `Optional.of(x)`: it is exactly our `Sourced.of` — a static method on the
class that news up the box for you.

One sharp detail — see the `static <T>` in our factory? Static methods **cannot use the
record's `T`**, because `T` only gets a meaning when an instance exists, and static methods
run without an instance. So the factory declares its **own** placeholder. That leading
`<T>` is a *declaration* — "this method introduces a placeholder" — and it's about to
explain the `map` signature too.

---

## Part 4 — `Function<T, R>`: behaviour in a variable

Our box can hold data. Now we want to hold *behaviour* — "a transformation from one type to
another" — in a variable, so we can pass it around like data. Java's shape for that is a
JDK interface (layer 2 — library, not magic). Its entire definition is essentially:

```java
// This is (essentially) the JDK's own definition — you never write this, you use it
@FunctionalInterface
public interface Function<T, R> {
    R apply(T t);        // "given a T, produce an R"
}
```

An interface with exactly one abstract method is a **functional interface**, and Java lets
you implement it with a lambda. These three declarations create the *same thing*:

```java
// In Main — three ways to write "a function from BankTransaction to String":

// 1. Anonymous class — the long form a lambda is shorthand for
Function<BankTransaction, String> f1 = new Function<>() {
    @Override public String apply(BankTransaction tx) { return tx.merchantName(); }
};

// 2. Lambda — same object, compact
Function<BankTransaction, String> f2 = tx -> tx.merchantName();

// 3. Method reference — when the lambda only calls one existing method
Function<BankTransaction, String> f3 = BankTransaction::merchantName;
```

And *running* one is a plain method call — you call the interface's one method:

```java
String merchant = f2.apply(tx);     // "Pret A Manger"
```

Triangle to keep in your head: **`Function` is the shape, the lambda is the implementation,
`apply` is the door you call.**

---

## Part 5 — `map`: transform the payload, keep the receipt

Now combine Parts 2 and 4. Here's the situation `map` exists for. You have a
`Sourced<BankTransaction>` and want the merchant name — **but you still want the raw JSON
kept with it**, because provenance is the entire point of the box. Without `map`:

```java
// The manual dance — unwrap, transform, re-wrap, remembering the receipt:
Sourced<BankTransaction> sourced = Sourced.of(tx, rawJson);

String merchant = f2.apply(sourced.payload());               // transform (receipt lost!)
Sourced<String> merchantSourced =
        new Sourced<>(merchant, sourced.rawJson());          // manually re-attach receipt
```

It works, but every call site must remember the re-attach step — forget it once and
provenance silently falls off. So we teach the box to do the dance itself:

```java
// ── file: Sourced.java (version 3 — final) ──────────────────
import java.util.Objects;
import java.util.function.Function;

public record Sourced<T>(T payload, String rawJson) {

    public static <T> Sourced<T> of(T payload, String rawJson) {
        Objects.requireNonNull(payload, "payload must not be null");
        return new Sourced<>(payload, rawJson);
    }

    /** Transform the payload; keep the provenance. */
    public <U> Sourced<U> map(Function<T, U> fn) {
        return new Sourced<>(fn.apply(payload), rawJson);
    }

    /** Records may override their generated methods — never print raw payloads. */
    @Override
    public String toString() {
        return "Sourced[" + payload + ", rawJson=<redacted>]";
    }
}
```

Now your questions, against *our own* method — no Optional in sight.

### Q: "Why the `<U>` in front? Can't we just have `U` as the return type?"

If you wrote the signature without the leading `<U>`:

```java
public Sourced<U> map(Function<T, U> fn)     // ❌ compile error: cannot find symbol U
```

the compiler goes hunting for a **class named `U`** — because nothing told it `U` is a
placeholder. Placeholders must be *declared* before use, like variables. The record's line
declared `T`; nothing has declared `U`. The leading `<U>` is that declaration, and Java's
grammar puts it just before the return type because the return type already mentions `U`,
so it's the only slot that comes early enough:

```java
public <U> Sourced<U> map(Function<T, U> fn)
//     ───            declaration: "this method introduces a placeholder called U"
//         ─────────  use                    ─  use
```

Why declare it on the **method** and not add it to the record (`Sourced<T, U>`)? Lifetime.
`T` is fixed the moment a box is created — a `Sourced<BankTransaction>` has
`T = BankTransaction` forever. But the *result* type of a mapping is chosen **fresh at
every call**, by whatever function the caller passes:

```java
Sourced<BankTransaction> s = Sourced.of(tx, rawJson);

Sourced<String> name   = s.map(t -> t.merchantName());      // this call: U = String
Sourced<Long>   amount = s.map(t -> t.amountMinorUnits());  // this call: U = Long
```

Same instance, two calls, two different `U`s. A class-level `U` would force you to commit
to one result type at construction time, which is nonsense. Also notice you never wrote
`U = String` anywhere — the compiler **inferred** it from the lambda you passed.

### Q: "How can `map` return a `Sourced`? Surely it returns the type it's mapped to?"

The mapped-to type is exactly what the `U` in `Sourced<U>` *is* — the payload type changed
from `T` to `U`; the box around it stayed. And that's the entire reason `map` exists.
Look at the two tools you now have side by side:

```java
String bare = f2.apply(s.payload());   // apply: bare result — provenance GONE
Sourced<String> kept = s.map(f2);      // map:   same transformation — provenance KEPT
```

If `map` returned the bare `U`, it would literally be `apply` with extra steps — you
already have `apply` for that. `map` is the "stay in the box" operation, and staying in
the box is what lets you **chain** transformations with the receipt surviving the trip:

```java
Sourced<Integer> nameLength =
        s.map(BankTransaction::merchantName)   // Sourced<String>  — receipt still aboard
         .map(String::length);                 // Sourced<Integer> — receipt STILL aboard

System.out.println(nameLength.payload());      // 13
System.out.println(nameLength.rawJson());      // the original Monzo JSON, untouched
```

And when you *do* finally want the bare value, that's what `payload()` is for — the last
line of a chain, not the middle. **Transform with `map`, exit the box with `payload()`.**

---

## Part 6 — The whole program

Everything above, assembled. Three files, runnable as-is (drop them in a folder,
`java Main.java` on any modern JDK):

```java
// ── BankTransaction.java ────────────────────────────────────
public record BankTransaction(String externalId, long amountMinorUnits, String merchantName) { }
```

```java
// ── Sourced.java ────────────────────────────────────────────
import java.util.Objects;
import java.util.function.Function;

public record Sourced<T>(T payload, String rawJson) {

    public static <T> Sourced<T> of(T payload, String rawJson) {
        Objects.requireNonNull(payload, "payload must not be null");
        return new Sourced<>(payload, rawJson);
    }

    public <U> Sourced<U> map(Function<T, U> fn) {
        return new Sourced<>(fn.apply(payload), rawJson);
    }

    @Override
    public String toString() {
        return "Sourced[" + payload + ", rawJson=<redacted>]";
    }
}
```

```java
// ── Main.java ───────────────────────────────────────────────
import java.util.function.Function;

public class Main {
    public static void main(String[] args) {
        String rawJson = "{\"id\":\"tx_001\",\"amount\":-450,\"merchant\":\"Pret A Manger\"}";
        BankTransaction tx = new BankTransaction("tx_001", -450, "Pret A Manger");

        Sourced<BankTransaction> sourced = Sourced.of(tx, rawJson);

        Function<BankTransaction, String> merchantOf = BankTransaction::merchantName;
        System.out.println(merchantOf.apply(tx));         // Pret A Manger  (bare call)

        Sourced<String> merchant = sourced.map(merchantOf);
        System.out.println(merchant.payload());           // Pret A Manger
        System.out.println(merchant.rawJson());           // {"id":"tx_001",...}  (kept!)

        Sourced<Integer> nameLength = sourced
                .map(BankTransaction::merchantName)
                .map(String::length);
        System.out.println(nameLength.payload());         // 13
        System.out.println(nameLength);                   // Sourced[13, rawJson=<redacted>]
    }
}
```

---

## Part 7 — Now, and only now: `Optional`

With the program built, one paragraph on the JDK's most famous box. `Optional<T>` is a
class in `java.util` with the **same architecture** you just built: a generic wrapper
(its context is *presence* — "is there a value at all?" — where ours is *provenance*),
a static factory (`Optional.of(x)` — same move as our `Sourced.of`), and a `map` that
transforms the value while preserving the context (an empty Optional stays empty through
`map`, the way our `rawJson` rides through ours). When you eventually read Optional's
source, you'll recognise every part — different context, same shape. That's the pattern:
**learn one box properly and you've learned them all.**

---

## Part 8 — Check yourself

**A. Predict the compiler.** Which lines compile? For each failure, name the reason:

```java
Sourced<BankTransaction> s = Sourced.of(tx, rawJson);

Sourced<String>  a = s.map(t -> t.merchantName());
Sourced<Long>    b = s.map(BankTransaction::amountMinorUnits);
String           c = s.map(t -> t.merchantName());
Sourced<Integer> d = s.map(t -> t.merchantName()).map(String::length);
Sourced<String>  e = s.map(t -> t.amountMinorUnits());
```

**B. One-sentence answers, in your own words:**
1. What does `apply` do, and who provides its implementation when you write a lambda?
2. Why is `U` declared on `map` rather than on the record next to `T`?
3. Why does `map` re-wrap in `new Sourced<>(...)` instead of returning `fn.apply(payload)` directly?
4. `Sourced.of` and `Optional.of` — what kind of method are they, and what can they do that `new` can't?

**C. Build.** Run the Part 6 program, then add a `Sourced<Long> absAmount` that maps the
transaction to its **absolute** amount (`Math.abs`). One extra line in `Main`.

---

*Lesson 2 (later, once this feels boring): why library code writes
`Function<? super T, ? extends U>` — the invariance problem and PECS.
Nothing in this lesson is wrong without it; the wildcards only widen who's allowed to call.*

---

## Appendix — Q&A from the read-through

Questions Alexander asked while reading, kept here as reference.

### Q1: What does "factories can return a hidden implementation" really mean?

`List` is an interface; `new` forces you to name a concrete class and always gives exactly
that class. A factory's declared return type is the interface, so it may return anything
that implements it — including classes you cannot name:

```java
System.out.println(new ArrayList<>().getClass()); // class java.util.ArrayList
System.out.println(List.of(1, 2).getClass());     // class java.util.ImmutableCollections$ListN — package-private!
```

Three powers `new` lacks: (1) choose the best implementation per situation and swap it in
later JDK releases without breaking callers; (2) return an existing object instead of
allocating (`Optional.empty()` is one shared singleton; `Integer.valueOf(7)` is cached);
(3) enforce invariants at the only entrance (`List.of` results reject `add` — immutability
guaranteed because the factory is the sole door). `Sourced` is a record (implicitly final,
no subtypes) so power 1 doesn't apply to `Sourced.of` — ours earns its keep via validation
and readability.

### Q2: When does a method need the leading `<X>` declaration?

> **A method declares a leading `<X>` exactly when it introduces a placeholder that isn't
> already in scope.**

- Instance method using only the record's `T` → declares nothing (`T` is in scope, fixed at
  construction: `public Sourced<T> something(T arg)`).
- Instance method introducing a new result type → declares only the new one (`map`'s `<U>`).
- Static method → declares everything it uses; the record's `T` is *not in scope* in static
  context (no instance exists to give it meaning). The `T` in `static <T> Sourced<T> of(...)`
  is an unrelated placeholder that merely reuses the name — rename it `<P>` and nothing changes.

### Q3: Should `Sourced` have a `withPayload(T newPayload)` method?

No — and the reason is the most important design idea in the series so far. `Sourced`'s
meaning is a claim: *"this payload was derived from this rawJson."* `map` preserves the claim
(the new payload is computed **from** the old one: `fn.apply(payload)` — the derivation chain
`merchant name ← transaction ← JSON` stays intact). `withPayload` accepts a value from
anywhere, severing the chain — it lets you build a `Sourced` whose claim is a lie. Rule:
**a type's methods should be the operations that keep its meaning true.**

Related: records give **shallow** immutability — the field can't be re-pointed, but a mutable
payload can still be mutated *through* the reference (`Sourced<List<String>>` →
`s.payload().add(...)` compiles and runs). Deep immutability requires the whole type tree to
be immutable — which `Sourced<BankTransaction>` is. And since immutable types can't be
edited, all "modification" is construction of new instances: `map` (and `String.toUpperCase`,
and every "wither") are instance methods that act as little factories.

### Q4: How do `map` and `Function` divide the work?

**The box provides `map`, you provide the function; `map` does the plumbing, the function
does the thinking.** `map` knows the box (how to open, rebuild, what context to carry) and
nothing about the transformation; the `Function` knows the transformation and has never heard
of the box. Because the function is box-ignorant, one function works with every box that has
a `map` — and each box's `map` preserves *its own* context:

| Box | Context preserved | Its `map`'s behaviour |
|---|---|---|
| `Sourced<T>` | provenance | copies `rawJson` across; always applies the fn |
| `Optional<T>` | presence | applies fn only if present; empty flows through **retyped** (`Optional<String>` even when empty, so chains still type-check) |
| `Stream<T>` | the sequence | applies fn per element, keeps ordering |

Contractually `map` returns the same *wrapper*, never necessarily the same *payload type* —
the result's type argument is decided by the function's **output slot** (`Function<T, U>`'s
`U` is the same `U` as in the returned `Sourced<U>`).
