package dev.amfshr.budgeteer.service.transaction;

import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.api.common.PageResponse;
import dev.amfshr.budgeteer.api.transaction.dto.TransactionResponse;
import dev.amfshr.budgeteer.domain.transaction.Transaction;
import dev.amfshr.budgeteer.exception.ApiException;
import dev.amfshr.budgeteer.repository.TransactionRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Filtered, paged transaction reads. Date range is half-open {@code [from, to)}; the frontend
 * owns timezone policy by passing Instants (decision 6).
 */
@Service
public class TransactionQueryService {

    /** Stand-in upper bound so the JPQL never sees a null timestamp. */
    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T00:00:00Z");

    private final TransactionRepository transactionRepository;

    public TransactionQueryService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * One page of the user's transactions, newest first. A non-owned {@code accountId} yields
     * an empty page (the query is user-scoped — no info leak, no extra lookup).
     *
     * @throws ApiException VALIDATION_ERROR when {@code from >= to}
     */
    public PageResponse<TransactionResponse> list(UUID userId, @Nullable UUID accountId,
            @Nullable Instant from, @Nullable Instant to, int page, int size) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "from must be before to");
        }

        Page<Transaction> result = transactionRepository.findFiltered(
                userId, accountId,
                from != null ? from : Instant.EPOCH,
                to != null ? to : FAR_FUTURE,
                PageRequest.of(page, size));

        List<TransactionResponse> items = result.getContent().stream().map(this::toResponse).toList();
        return PageResponse.from(result, items);
    }

    private TransactionResponse toResponse(Transaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getAccount().getId(),
                tx.getAmountMinorUnits(),
                tx.getCurrency(),
                tx.getStatus().name(),
                tx.getDescription(),
                tx.getMerchantName(),
                tx.getMerchantCategory(),
                tx.getNotes(),
                tx.isExcludedFromAnalytics(),
                tx.getOccurredAt(),
                tx.getSettledAt());
    }
}
