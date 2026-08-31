package dev.amfshr.budgeteer.service.ingest;

import dev.amfshr.budgeteer.domain.account.Account;
import dev.amfshr.budgeteer.domain.account.AccountType;
import dev.amfshr.budgeteer.domain.account.Provider;
import dev.amfshr.budgeteer.domain.monzo.MonzoAccount;
import dev.amfshr.budgeteer.domain.monzo.MonzoTransaction;
import dev.amfshr.budgeteer.repository.AccountRepository;
import dev.amfshr.budgeteer.repository.MonzoAccountRepository;
import dev.amfshr.budgeteer.repository.MonzoTransactionRepository;
import dev.amfshr.budgeteer.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Raw {@code monzo_*} → domain ingest. Reads ALL raw accounts (closed ones must archive),
 * maps raw transactions re-touched since the per-account cursor, and advances the cursor to
 * the max processed raw {@code updated_at} (never {@code now()} — decision 10).
 */
@Service
public class MonzoIngestor implements ProviderIngestor {

    private static final Logger log = LoggerFactory.getLogger(MonzoIngestor.class);

    private static final String INSTITUTION_NAME = "Monzo";

    private static final Map<String, AccountType> TYPE_MAP = Map.of(
            "uk_retail", AccountType.CURRENT,
            "uk_retail_joint", AccountType.CURRENT,
            "uk_monzo_flex", AccountType.CREDIT_CARD
    );

    private final MonzoAccountRepository monzoAccountRepository;
    private final MonzoTransactionRepository monzoTransactionRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public MonzoIngestor(
            MonzoAccountRepository monzoAccountRepository,
            MonzoTransactionRepository monzoTransactionRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository
    ) {
        this.monzoAccountRepository = monzoAccountRepository;
        this.monzoTransactionRepository = monzoTransactionRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Override
    public Provider provider() {
        return Provider.MONZO;
    }

    @Override
    public List<Account> ingestAccounts() {
        List<Account> collected = new ArrayList<>();
        for (MonzoAccount raw : monzoAccountRepository.findAll()) {
            Optional<Account> existing =
                    accountRepository.findByProviderAndProviderAccountId(Provider.MONZO, raw.getId());

            Account domain;
            if (existing.isEmpty()) {
                domain = new Account(
                        raw.getUser(),
                        Provider.MONZO,
                        raw.getId(),
                        normalise(raw.getAccountType()),
                        INSTITUTION_NAME,
                        raw.getDescription(),
                        raw.getCurrency()
                );
            } else {
                domain = existing.get();
                domain.setAccountType(normalise(raw.getAccountType()));   // provider-owned, refresh ok
            }

            if (raw.isClosed() || !raw.getConnection().isActive()) {
                domain.archive();
            } else {
                domain.unarchive();
            }
            collected.add(accountRepository.save(domain));
        }
        return collected;
    }

    @Override
    public void ingestTransactions(Account account) {
        Instant cursor = account.getRawSyncedThrough() != null
                ? account.getRawSyncedThrough()
                : Instant.EPOCH;
        List<MonzoTransaction> rows =
                monzoTransactionRepository.findByAccountIdUpdatedAfter(account.getProviderAccountId(), cursor);

        Instant maxUpdated = cursor;
        for (MonzoTransaction raw : rows) {
            if (raw.getUpdatedAt().isAfter(maxUpdated)) {
                maxUpdated = raw.getUpdatedAt();
            }
            if (raw.isDeclined()) {
                continue;                                    // never mapped; cursor still advances
            }
            transactionRepository.upsert(
                    account.getUser().getId(),
                    account.getId(),
                    Provider.MONZO.name(),
                    raw.getId(),
                    raw.getAmount(),                          // widens INTEGER → BIGINT
                    raw.getCurrency(),
                    (raw.getMonzoSettledAt() == null ? "PENDING" : "SETTLED"),
                    raw.getDescription(),
                    raw.getMerchantName(),
                    raw.getMerchantCategory(),
                    raw.getNotes(),                           // seed-on-insert only (update set omits it)
                    raw.getMonzoCreatedAt(),
                    raw.getMonzoSettledAt()
            );
        }

        if (maxUpdated.isAfter(cursor)) {
            account.setRawSyncedThrough(maxUpdated);
            accountRepository.save(account);
        }
    }

    private AccountType normalise(String rawType) {
        AccountType type = TYPE_MAP.get(rawType);
        if (type == null) {
            log.warn("Unknown Monzo account type '{}' — mapping to OTHER", rawType);
            return AccountType.OTHER;
        }
        return type;
    }
}
