# Code Structure Refactoring

> **Priority:** HIGH | **Estimate:** 1 day | **Status:** ✅ Complete (merged March 2026)
>
> Delivered: `repository/` package now holds all repositories (`UserRepository` et al. moved out of `domain/`), and `service/` is split into `service/auth`, `service/monzo`, `service/common`. The "Current State (Problems)" below is the pre-refactor layout, kept for historical context.

## Goal

Clean separation of concerns. Domain entities should not be mixed with repository interfaces, and services/clients should be organised by feature rather than as a flat list.

## Current State (Problems)

```
domain/user/
├── User.java             # Entity ✅
└── UserRepository.java   # Repository ❌ wrong layer

service/
├── AuthService.java         # flat — 10+ files ❌
├── MonzoOAuthService.java
└── ...
```

## Target State

```
domain/user/User.java              # Entity only

repository/
└── UserRepository.java            # Separate layer

service/
├── auth/        # AuthService, SessionService, JweTokenService
├── monzo/       # MonzoOAuthService, MonzoConnectionService
└── common/      # EmailService, EncryptionService, CookieService

client/
└── monzo/MonzoClient.java         # External API clients
```

## Scope

- [ ] Move all `*Repository.java` files to new `repository/` package
- [ ] Organise services into feature subpackages (`auth/`, `monzo/`, `common/`)
- [ ] Create `client/` package for external API clients
- [ ] Update imports across all affected files
- [ ] Add `@NullMarked` `package-info.java` to each new package
- [ ] Run full test suite to verify nothing broken
