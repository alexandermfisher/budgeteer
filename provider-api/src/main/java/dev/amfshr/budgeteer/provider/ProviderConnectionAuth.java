package dev.amfshr.budgeteer.provider;

import dev.amfshr.budgeteer.provider.model.BankIdentity;
import dev.amfshr.budgeteer.provider.model.BankTokens;

/**
 * Connection-lifecycle capability: OAuth authorization, token exchange/refresh, and
 * identifying the resulting connection at the provider.
 *
 * <p>Every provider integration implements this; the data capabilities
 * ({@link AccountsCapability}, {@link BalanceCapability}, {@link TransactionsCapability})
 * are implemented per the provider's supported feature set.
 */
public interface ProviderConnectionAuth {

    /** Build the provider's OAuth authorization URL for the given CSRF state. */
    String buildAuthorizationUrl(String state);

    /** Exchange an authorization code for tokens (redirect URI etc. come from the impl's config). */
    BankTokens exchangeCode(String code);

    /** Refresh an access token. Implementations fall back to the old refresh token if not rotated. */
    BankTokens refreshTokens(String refreshToken);

    /** Identify the connection at the provider (Monzo user_id; TrueLayer credentials_id + consent). */
    BankIdentity getIdentity(String accessToken);
}
