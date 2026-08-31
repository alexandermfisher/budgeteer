package dev.amfshr.budgeteer.service.ingest;

import dev.amfshr.budgeteer.domain.account.Account;
import dev.amfshr.budgeteer.domain.account.Provider;
import dev.amfshr.budgeteer.domain.monzo.MonzoAccount;
import dev.amfshr.budgeteer.provider.BalanceCapability;
import dev.amfshr.budgeteer.provider.exception.ProviderConnectionRevokedException;
import dev.amfshr.budgeteer.provider.model.BankBalance;
import dev.amfshr.budgeteer.repository.AccountRepository;
import dev.amfshr.budgeteer.repository.MonzoAccountRepository;
import dev.amfshr.budgeteer.service.monzo.MonzoConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stamps provider balance snapshots onto domain accounts (L3: stored snapshot, never derived
 * from transactions). No class-level transaction — each save commits alone, so a mid-run
 * failure loses nothing already stamped.
 */
@Service
public class BalanceRefreshService {

    private static final Logger log = LoggerFactory.getLogger(BalanceRefreshService.class);

    private final MonzoAccountRepository monzoAccountRepository;
    private final AccountRepository accountRepository;
    private final MonzoConnectionService connectionService;
    private final BalanceCapability balanceCapability;

    public BalanceRefreshService(
            MonzoAccountRepository monzoAccountRepository,
            AccountRepository accountRepository,
            MonzoConnectionService connectionService,
            BalanceCapability balanceCapability
    ) {
        this.monzoAccountRepository = monzoAccountRepository;
        this.accountRepository = accountRepository;
        this.connectionService = connectionService;
        this.balanceCapability = balanceCapability;
    }

    /** Refreshes every syncable account's balance, isolating failures per account/connection. */
    public void refreshAll() {
        Map<UUID, List<MonzoAccount>> byConnection = monzoAccountRepository.findAllSyncable().stream()
                .collect(Collectors.groupingBy(a -> a.getConnection().getId(),
                        LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<UUID, List<MonzoAccount>> entry : byConnection.entrySet()) {
            UUID connectionId = entry.getKey();
            List<MonzoAccount> rawAccounts = entry.getValue();

            String accessToken;
            try {
                accessToken = connectionService.getDecryptedAccessToken(
                        connectionId, rawAccounts.getFirst().getUserId());
            } catch (Exception e) {
                log.warn("Balance refresh: skipping connection {} - {}", connectionId, e.getMessage());
                continue;
            }

            refreshConnection(connectionId, rawAccounts, accessToken);
        }
    }

    private void refreshConnection(UUID connectionId, List<MonzoAccount> rawAccounts, String accessToken) {
        for (MonzoAccount raw : rawAccounts) {
            Optional<Account> domain =
                    accountRepository.findByProviderAndProviderAccountId(Provider.MONZO, raw.getId());
            if (domain.isEmpty()) {
                continue;                                   // mapping hasn't created it yet
            }
            try {
                BankBalance balance = balanceCapability.getBalance(accessToken, raw.getId());
                if (!balance.currency().equals(domain.get().getCurrency())) {
                    log.warn("Balance currency {} != account currency {} [account={}] — storing anyway",
                            balance.currency(), domain.get().getCurrency(), domain.get().getId());
                }
                domain.get().recordBalance(balance.balanceMinorUnits(), Instant.now());
                accountRepository.save(domain.get());
            } catch (ProviderConnectionRevokedException e) {
                log.warn("Balance refresh: connection {} revoked — abandoning its remaining accounts",
                        connectionId);
                break;                                      // rest of this connection is dead
            } catch (Exception e) {
                log.warn("Balance refresh failed [account={}] - {}", raw.getId(), e.getMessage());
            }
        }
    }
}
