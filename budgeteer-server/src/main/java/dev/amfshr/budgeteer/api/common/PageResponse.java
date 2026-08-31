package dev.amfshr.budgeteer.api.common;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * House paging envelope — the wire shape for every paged list from now on. Owned by us so the
 * JSON contract never shifts underneath the frontend when Spring's paging internals change.
 */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    /** Wraps a Spring page's metadata around already-mapped DTO items. */
    public static <T, R> PageResponse<R> from(Page<T> page, List<R> items) {
        return new PageResponse<>(items, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
