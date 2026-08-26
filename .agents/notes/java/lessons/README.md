# Java Lessons

Running series of Java lessons tied to real Budgeteer work — each lesson grows out of code
the project actually needs, so learning the language and building the software are the same
activity. Two goals, per Alexander:

1. **Build the software** — and absorb the architectural lessons in it (contracts, capability
   interfaces, module boundaries).
2. **Understand Java deeply enough to write better Java** — not just make it compile.

## Format

- One connected example program per lesson — every snippet belongs to the same small set of
  files, buildable and runnable; no orphaned code fragments.
- New syntax/terms defined at first use; JDK types vs. project types always distinguished.
- Ends with exercises (predict-the-compiler, own-words questions, a small build task).
  Answers get reviewed in-session like a PR.
- Pace over coverage: hard topics (e.g. wildcards) are deferred until the foundations are
  boring, then get their own lesson.

## Lessons

| # | Lesson | Project tie-in | Status |
|---|--------|----------------|--------|
| 01 | [Generics, functions, and `map`](01-generics-functions-map.md) — type parameters, the generic-wrapper pattern, `Function`/`apply`, static factories (`.of`), `map` | `Sourced<T>` envelope for task #12 | ✅ delivered; exercises pending review |
| 02 | Wildcards & PECS — invariance, `? super T` / `? extends R`, reading JDK signatures | Upgrading `Sourced.map` to the library-grade signature | 📋 planned |

*Ideas for future lessons as the project surfaces them: interfaces & default methods (capability
contracts), exceptions & the provider exception hierarchy, Streams (ingest pipeline), Optional
in earnest, records vs classes vs sealed types.*
