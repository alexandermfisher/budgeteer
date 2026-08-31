# Java Generics, One Program at a Time

**Lesson 2: wildcards and PECS** — why library code writes `Function<? super T, ? extends U>`.

Continues the Lesson 1 program (`BankTransaction`, `Sourced`, `Main`). Prerequisite: Lesson 1
feels boring. Nothing from Lesson 1 was *wrong* — this lesson only changes **who is allowed
to call** `map`; every call that compiled before still compiles after.

---

## Part 1 — Hitting the wall

Our `map` from Lesson 1:

```java
public <U> Sourced<U> map(Function<T, U> fn) {
    return new Sourced<>(fn.apply(payload), rawJson);
}
```

Every call so far passed the function **inline as a lambda**, and everything worked. Now do
what real codebases do: store functions in variables and reuse them. Add to `Main`:

```java
Sourced<BankTransaction> s = Sourced.of(tx, rawJson);

// A general-purpose describer — works on ANY object, so we wrote it that way:
Function<Object, String> describe = Object::toString;

Sourced<String> label = s.map(describe);     // ❌ DOES NOT COMPILE
```

Think about whether this *should* work: `map` will feed the function a `BankTransaction`,
and `describe` accepts **any `Object`** — a `BankTransaction` certainly is one. Logically
watertight. The compiler still refuses. Second wall:

```java
Function<BankTransaction, Long> amountOf = BankTransaction::amountMinorUnits;

Sourced<Number> n = s.map(amountOf);         // ❌ DOES NOT COMPILE
```

Again, should it? The function produces `Long`s; every `Long` **is a** `Number`; we asked
for a box of `Number`. Logically fine. Refused anyway.

Both rejections come from one rule, so let's meet the rule.

## Part 2 — The rule: generics are invariant

`Long` is a subtype of `Number`. But **`Sourced<Long>` has no relationship whatsoever to
`Sourced<Number>`** — neither is a subtype of the other. Same for `List<Long>` vs
`List<Number>`, and `Function<Object,String>` vs `Function<BankTransaction,String>`.
Type arguments do **not** pass their relationships up to the generic type. This is called
**invariance**, and it exists because the alternative corrupts data:

```java
List<Long> longs = new ArrayList<>();
List<Number> numbers = longs;    // ❌ imagine this compiled...
numbers.add(3.14);               // a Double enters through the Number-shaped door
Long boom = longs.get(0);        // ...and a List<Long> now yields a Double. Crash.
```

Because a `List` can be **written to**, letting `List<Long>` masquerade as `List<Number>`
would let callers poison it. Java's default answer: no relationship, ever, for anybody.

Sound — but *over*-strict. The poisoning attack needed the write direction. If a use site
only ever **reads** from the generic thing, or only ever **writes** to it, the danger is
gone and the strictness buys nothing. Wall 1 and Wall 2 are exactly such sites. Wildcards
are how you tell the compiler that.

## Part 3 — `? super T`: "accepts T, or anything broader"

Look at how `map` actually *uses* `fn` with respect to `T`: one place —

```java
fn.apply(payload)     // map PUSHES a T into the function. That's all it ever does with T.
```

`map` only ever **feeds** `T`s in. A function declared to accept `Object` can obviously
digest a `BankTransaction` — anything built for the general case handles the specific case.
So we relax the input slot:

```java
public <U> Sourced<U> map(Function<? super T, U> fn)
//                                 ^^^^^^^^^ "T, or any supertype of T"
```

Read `? super T` as: *the function's input type may be `T` itself or anything above it in
the hierarchy*. Now Wall 1 compiles: `Function<Object, String>` matches
`Function<? super BankTransaction, String>` because `Object` is a supertype of
`BankTransaction`. Safety is preserved because `map` still only feeds it `BankTransaction`s
— and a `BankTransaction` counts as an `Object`.

## Part 4 — `? extends U`: "produces U, or anything narrower"

Now how does `map` use `fn` with respect to the result: one place —

```java
new Sourced<>(fn.apply(payload), rawJson)   // map PULLS a result out and stores it as a U
```

`map` only ever **takes** results out. If the caller wants a `Sourced<Number>` and the
function produces `Long`s — every value pulled out is a `Long`, and a `Long` *is a*
`Number`. Storing it as a `Number` cannot go wrong. So we relax the output slot:

```java
public <U> Sourced<U> map(Function<? super T, ? extends U> fn)
//                                            ^^^^^^^^^^^ "U, or any subtype of U"
```

Read `? extends U` as: *the function may produce `U` itself or anything below it*. Now
Wall 2 compiles: with the target `Sourced<Number>`, the compiler picks `U = Number` and
accepts the `Long`-producing function because `Long extends Number`.

## Part 5 — PECS, and the finished signature

The mnemonic for which wildcard goes where: **PECS — Producer Extends, Consumer Super** —
stated from the perspective of the generic thing you're using (here, `fn`):

- `fn` **consumes** payloads (`T`s flow *into* it) → its input slot gets `? super T`
- `fn` **produces** results (`U`s flow *out* of it) → its output slot gets `? extends U`

Same rule in one line: *only putting things in → `super`; only taking things out →
`extends`; doing both → no wildcard allowed* (which is why the `List<Number> = longs`
assignment stays banned — a plain `List` reference is used in both directions).

The final `Sourced.java`, upgraded — this is the only change Lesson 2 makes to the program:

```java
// ── file: Sourced.java (version 4 — final) ──────────────────
import java.util.Objects;
import java.util.function.Function;

public record Sourced<T>(T payload, String rawJson) {

    public static <T> Sourced<T> of(T payload, String rawJson) {
        Objects.requireNonNull(payload, "payload must not be null");
        return new Sourced<>(payload, rawJson);
    }

    /** Transform the payload; keep the provenance. */
    public <U> Sourced<U> map(Function<? super T, ? extends U> fn) {
        return new Sourced<>(fn.apply(payload), rawJson);
    }

    @Override
    public String toString() {
        return "Sourced[" + payload + ", rawJson=<redacted>]";
    }
}
```

Every Lesson 1 call still compiles — widening a parameter only ever admits *more* callers.
And note what did **not** change: the body. Wildcards are purely about who may call.

Two honest footnotes:

1. **Why didn't the lambdas hit the wall in Lesson 1?** An inline lambda has no fixed type —
   the compiler shapes it to fit the parameter (target typing). The wall only appears when a
   function already has a committed type: stored in a variable, passed through layers, or a
   method reference assigned earlier. Libraries can't predict which callers do that, so
   library code always writes the wildcards. Your inline-lambda code usually never needs to.
2. **You rarely *write* wildcards; you constantly *read* them.** The skill this lesson buys
   is reading JDK signatures without flinching.

## Part 6 — Reading real signatures in the wild

All of these are in code Budgeteer already calls. Decode the wildcard in each using PECS:

```java
// Optional — same shape as ours, letter-for-letter:
public <U> Optional<U> map(Function<? super T, ? extends U> mapper)

// Stream — identical pattern again:
public <R> Stream<R> map(Function<? super T, ? extends R> mapper)

// List.sort — the comparator CONSUMES elements to compare them → super:
public void sort(Comparator<? super E> c)

// Collection.addAll — the source collection PRODUCES elements for us to copy in → extends:
public boolean addAll(Collection<? extends E> c)
```

If you can say *why* `sort` is `super` but `addAll` is `extends`, PECS is yours: the
comparator eats elements (consumer), the source collection supplies them (producer).

---

## Part 7 — Check yourself

**A. Predict the compiler** — with the upgraded (wildcard) `map`:

```java
Sourced<BankTransaction> s = Sourced.of(tx, rawJson);
Function<Object, Integer> hash = Object::hashCode;
Function<BankTransaction, Long> amt = BankTransaction::amountMinorUnits;
Function<String, Integer> len = String::length;

Sourced<Integer> a = s.map(hash);
Sourced<Number>  b = s.map(amt);
Sourced<Object>  c = s.map(hash);
Sourced<Integer> d = s.map(len);
Sourced<Long>    e = s.map(hash);
```

**B. Own words, one or two sentences each:**
1. Why is `Sourced<Long>` not a subtype of `Sourced<Number>`, when `Long` is a subtype of `Number`?
2. State PECS and apply it to `map`'s parameter from `fn`'s point of view.
3. Why is it *safe* for `map` to accept a `Function<Object, String>` when `T` is `BankTransaction`?

**C. Project tie-in (this is the #12 work):** write the real `Sourced<T>` in
`provider-api`'s `.model` package with the wildcard `map`, jspecify `@Nullable` on
`rawJson`, and the redacting `toString` — then we wire it into `BankTransactionPage`
together.

---

*Possible Lesson 3 topics, whichever the project surfaces first: bounded type parameters
(`<T extends Comparable<T>>`); interfaces & default methods (extending the capability
contracts); Streams (the ingest pipeline).*
