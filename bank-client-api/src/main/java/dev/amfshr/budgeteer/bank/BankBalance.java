package dev.amfshr.budgeteer.bank;

/**
 * Provider-neutral account balance snapshot. {@code balanceMinorUnits} is signed
 * (a credit card in debt is negative). Lean by policy: only what the app persists —
 * provider extras (available balance, credit limit) arrive later as {@code @Nullable} additions.
 */
public record BankBalance(
        long balanceMinorUnits,
        String currency
) {}
