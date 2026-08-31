package dev.amfshr.budgeteer.domain.account;

/**
 * The external service holding the connection and answering our API calls — NOT the bank behind
 * an account (that's the institution, e.g. "Monzo" or "Lloyds" in {@code institution_name}).
 * A single TrueLayer connection can yield accounts at many institutions.
 */
public enum Provider {
    MONZO,
    TRUELAYER
}
