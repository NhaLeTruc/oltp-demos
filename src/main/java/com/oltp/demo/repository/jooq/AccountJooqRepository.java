package com.oltp.demo.repository.jooq;

import static org.jooq.impl.DSL.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * jOOQ repository demonstrating type-safe SQL query construction.
 *
 * Showcases jOOQ features:
 * - Type-safe complex queries with compile-time validation
 * - Multi-table joins with proper typing
 * - Aggregations and window functions
 * - Batch operations
 * - CTE (Common Table Expressions)
 * - Dynamic query building
 *
 * Compared to JPA:
 * - More control over SQL generation
 * - Better performance for complex queries
 * - Type-safe yet close to raw SQL
 * - No N+1 query issues
 *
 * @see <a href="specs/001-oltp-core-demo/spec.md">US3: Performance Under Load</a>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AccountJooqRepository {

    private final DSLContext dsl;

    // Table references (using string names until jOOQ code generation)
    private static final Table<?> ACCOUNTS = table("accounts");
    private static final Table<?> USERS = table("users");
    private static final Table<?> ACCOUNT_TYPES = table("account_types");
    private static final Table<?> TRANSACTIONS = table("transactions");
    private static final Table<?> TRANSFER_LOGS = table("transfer_logs");

    // Common field references
    private static final Field<Long> ACCOUNTS_ID = field("accounts.id", Long.class);
    private static final Field<Long> ACCOUNTS_USER_ID = field("accounts.user_id", Long.class);
    private static final Field<Integer> ACCOUNTS_TYPE_ID = field("accounts.account_type_id", Integer.class);
    private static final Field<BigDecimal> ACCOUNTS_BALANCE = field("accounts.balance", BigDecimal.class);
    private static final Field<String> ACCOUNTS_STATUS = field("accounts.status", String.class);
    private static final Field<LocalDateTime> ACCOUNTS_CREATED_AT = field("accounts.created_at", LocalDateTime.class);

    private static final Field<Long> USERS_ID = field("users.id", Long.class);
    private static final Field<String> USERS_USERNAME = field("users.username", String.class);
    private static final Field<String> USERS_EMAIL = field("users.email", String.class);
    private static final Field<String> USERS_FULL_NAME = field("users.full_name", String.class);

    private static final Field<Integer> ACCOUNT_TYPES_ID = field("account_types.id", Integer.class);
    private static final Field<String> ACCOUNT_TYPES_NAME = field("account_types.type_name", String.class);
    private static final Field<BigDecimal> ACCOUNT_TYPES_MIN_BALANCE = field("account_types.min_balance", BigDecimal.class);

    private static final Field<Long> TRANSACTIONS_ID = field("transactions.id", Long.class);
    private static final Field<Long> TRANSACTIONS_FROM_ACCOUNT = field("transactions.from_account_id", Long.class);
    private static final Field<Long> TRANSACTIONS_TO_ACCOUNT = field("transactions.to_account_id", Long.class);
    private static final Field<BigDecimal> TRANSACTIONS_AMOUNT = field("transactions.amount", BigDecimal.class);
    private static final Field<String> TRANSACTIONS_TYPE = field("transactions.transaction_type", String.class);
    private static final Field<String> TRANSACTIONS_STATUS = field("transactions.status", String.class);
    private static final Field<UUID> TRANSACTIONS_CORRELATION_ID = field("transactions.correlation_id", UUID.class);

    // =========================================================================
    // Type-Safe Complex Queries (T127)
    // =========================================================================

    /**
     * Finds accounts with users using a single optimized JOIN query.
     *
     * Demonstrates:
     * - Type-safe multi-table joins
     * - Avoiding N+1 queries
     * - Compile-time SQL validation
     *
     * @return List of account summaries with user info
     */
    public List<AccountWithUserDTO> findAccountsWithUsers() {
        return dsl
            .select(
                ACCOUNTS_ID,
                field("accounts.account_number", String.class),
                ACCOUNTS_BALANCE,
                ACCOUNTS_STATUS,
                USERS_USERNAME,
                USERS_EMAIL,
                USERS_FULL_NAME
            )
            .from(ACCOUNTS)
            .join(USERS).on(ACCOUNTS_USER_ID.eq(USERS_ID))
            .where(ACCOUNTS_STATUS.eq("ACTIVE"))
            .orderBy(ACCOUNTS_BALANCE.desc())
            .fetch()
            .map(r -> new AccountWithUserDTO(
                r.get(ACCOUNTS_ID),
                r.get(field("accounts.account_number", String.class)),
                r.get(ACCOUNTS_BALANCE),
                r.get(ACCOUNTS_STATUS),
                r.get(USERS_USERNAME),
                r.get(USERS_EMAIL),
                r.get(USERS_FULL_NAME)
            ));
    }

    /**
     * Complex query with multiple joins and aggregations.
     *
     * Demonstrates:
     * - 3-table joins
     * - Aggregation functions
     * - GROUP BY with HAVING
     * - Type-safe field references
     *
     * @param minTransactionCount Minimum number of transactions
     * @return Account summaries with transaction statistics
     */
    public List<AccountTransactionSummaryDTO> findAccountsWithTransactionStats(int minTransactionCount) {
        Field<Integer> txCount = count(TRANSACTIONS_ID).as("tx_count");
        Field<BigDecimal> totalDebited = sum(
            when(TRANSACTIONS_FROM_ACCOUNT.eq(ACCOUNTS_ID), TRANSACTIONS_AMOUNT)
            .otherwise(BigDecimal.ZERO)
        ).as("total_debited");
        Field<BigDecimal> totalCredited = sum(
            when(TRANSACTIONS_TO_ACCOUNT.eq(ACCOUNTS_ID), TRANSACTIONS_AMOUNT)
            .otherwise(BigDecimal.ZERO)
        ).as("total_credited");

        return dsl
            .select(
                ACCOUNTS_ID,
                field("accounts.account_number", String.class),
                ACCOUNTS_BALANCE,
                USERS_USERNAME,
                ACCOUNT_TYPES_NAME,
                txCount,
                totalDebited,
                totalCredited
            )
            .from(ACCOUNTS)
            .join(USERS).on(ACCOUNTS_USER_ID.eq(USERS_ID))
            .join(ACCOUNT_TYPES).on(ACCOUNTS_TYPE_ID.eq(ACCOUNT_TYPES_ID))
            .leftJoin(TRANSACTIONS).on(
                TRANSACTIONS_FROM_ACCOUNT.eq(ACCOUNTS_ID)
                .or(TRANSACTIONS_TO_ACCOUNT.eq(ACCOUNTS_ID))
            )
            .where(ACCOUNTS_STATUS.eq("ACTIVE"))
            .groupBy(
                ACCOUNTS_ID,
                field("accounts.account_number"),
                ACCOUNTS_BALANCE,
                USERS_USERNAME,
                ACCOUNT_TYPES_NAME
            )
            .having(count(TRANSACTIONS_ID).ge(minTransactionCount))
            .orderBy(txCount.desc())
            .fetch()
            .map(r -> new AccountTransactionSummaryDTO(
                r.get(ACCOUNTS_ID),
                r.get(field("accounts.account_number", String.class)),
                r.get(ACCOUNTS_BALANCE),
                r.get(USERS_USERNAME),
                r.get(ACCOUNT_TYPES_NAME),
                r.get(txCount),
                r.get(totalDebited),
                r.get(totalCredited)
            ));
    }

    /**
     * Query with window functions for ranking and analytics.
     *
     * Demonstrates:
     * - Window functions (ROW_NUMBER, RANK)
     * - Partitioning and ordering
     * - Type-safe window syntax
     *
     * @return Top 5 accounts by balance for each account type
     */
    public List<AccountRankingDTO> findTopAccountsByTypeWithRanking() {
        Field<Integer> rank = rowNumber()
            .over()
            .partitionBy(ACCOUNT_TYPES_NAME)
            .orderBy(ACCOUNTS_BALANCE.desc())
            .as("rank");

        Table<Record> rankedAccounts = dsl
            .select(
                ACCOUNTS_ID,
                field("accounts.account_number", String.class),
                ACCOUNTS_BALANCE,
                USERS_USERNAME,
                ACCOUNT_TYPES_NAME,
                rank
            )
            .from(ACCOUNTS)
            .join(USERS).on(ACCOUNTS_USER_ID.eq(USERS_ID))
            .join(ACCOUNT_TYPES).on(ACCOUNTS_TYPE_ID.eq(ACCOUNT_TYPES_ID))
            .where(ACCOUNTS_STATUS.eq("ACTIVE"))
            .asTable("ranked");

        return dsl
            .select()
            .from(rankedAccounts)
            .where(field("rank", Integer.class).le(5))
            .fetch()
            .map(r -> new AccountRankingDTO(
                r.get(field("id", Long.class)),
                r.get(field("account_number", String.class)),
                r.get(field("balance", BigDecimal.class)),
                r.get(field("username", String.class)),
                r.get(field("type_name", String.class)),
                r.get(field("rank", Integer.class))
            ));
    }

    /**
     * CTE (Common Table Expression) example for complex recursive or staged queries.
     *
     * Demonstrates:
     * - WITH clause (CTE)
     * - Multi-stage query composition
     * - Type-safe CTE references
     *
     * @param minBalance Minimum balance threshold
     * @return High-value accounts with enriched data
     */
    public List<HighValueAccountDTO> findHighValueAccountsWithCTE(BigDecimal minBalance) {
        // CTE: high_value_accounts
        Table<Record> highValueAccounts = dsl
            .select(
                ACCOUNTS_ID,
                ACCOUNTS_BALANCE,
                ACCOUNTS_USER_ID,
                ACCOUNTS_TYPE_ID
            )
            .from(ACCOUNTS)
            .where(ACCOUNTS_BALANCE.ge(minBalance))
            .and(ACCOUNTS_STATUS.eq("ACTIVE"))
            .asTable("high_value_accounts");

        return dsl
            .with(highValueAccounts)
            .select(
                field("high_value_accounts.id", Long.class),
                field("high_value_accounts.balance", BigDecimal.class),
                USERS_USERNAME,
                USERS_EMAIL,
                ACCOUNT_TYPES_NAME,
                ACCOUNT_TYPES_MIN_BALANCE
            )
            .from(highValueAccounts)
            .join(USERS).on(field("high_value_accounts.user_id", Long.class).eq(USERS_ID))
            .join(ACCOUNT_TYPES).on(field("high_value_accounts.account_type_id", Integer.class).eq(ACCOUNT_TYPES_ID))
            .orderBy(field("high_value_accounts.balance", BigDecimal.class).desc())
            .fetch()
            .map(r -> new HighValueAccountDTO(
                r.get(field("high_value_accounts.id", Long.class)),
                r.get(field("high_value_accounts.balance", BigDecimal.class)),
                r.get(USERS_USERNAME),
                r.get(USERS_EMAIL),
                r.get(ACCOUNT_TYPES_NAME),
                r.get(ACCOUNT_TYPES_MIN_BALANCE)
            ));
    }

    /**
     * Dynamic query building based on filter criteria.
     *
     * Demonstrates:
     * - Conditional query construction
     * - Type-safe dynamic WHERE clauses
     * - Flexible query composition
     *
     * @param filter Search criteria
     * @return Matching accounts
     */
    public List<AccountWithUserDTO> searchAccountsDynamic(AccountSearchFilter filter) {
        var query = dsl
            .select(
                ACCOUNTS_ID,
                field("accounts.account_number", String.class),
                ACCOUNTS_BALANCE,
                ACCOUNTS_STATUS,
                USERS_USERNAME,
                USERS_EMAIL,
                USERS_FULL_NAME
            )
            .from(ACCOUNTS)
            .join(USERS).on(ACCOUNTS_USER_ID.eq(USERS_ID));

        // Dynamic WHERE conditions
        if (filter.minBalance() != null) {
            query = query.where(ACCOUNTS_BALANCE.ge(filter.minBalance()));
        }

        if (filter.maxBalance() != null) {
            query = query.where(ACCOUNTS_BALANCE.le(filter.maxBalance()));
        }

        if (filter.status() != null) {
            query = query.where(ACCOUNTS_STATUS.eq(filter.status()));
        }

        if (filter.username() != null) {
            query = query.where(USERS_USERNAME.like("%" + filter.username() + "%"));
        }

        return query
            .orderBy(ACCOUNTS_BALANCE.desc())
            .limit(filter.limit() != null ? filter.limit() : 100)
            .fetch()
            .map(r -> new AccountWithUserDTO(
                r.get(ACCOUNTS_ID),
                r.get(field("accounts.account_number", String.class)),
                r.get(ACCOUNTS_BALANCE),
                r.get(ACCOUNTS_STATUS),
                r.get(USERS_USERNAME),
                r.get(USERS_EMAIL),
                r.get(USERS_FULL_NAME)
            ));
    }

    // =========================================================================
    // Batch Operations (T128)
    // =========================================================================

    /**
     * Batch insert demonstration using jOOQ.
     *
     * Demonstrates:
     * - Multi-row INSERT in single statement
     * - Type-safe value binding
     * - Better performance than individual inserts
     *
     * @param logs Transfer logs to insert
     * @return Number of rows inserted
     */
    @Transactional
    public int batchInsertTransferLogs(List<TransferLogBatchDTO> logs) {
        if (logs.isEmpty()) {
            return 0;
        }

        var insert = dsl.insertInto(
            TRANSFER_LOGS,
            field("transaction_id", Long.class),
            field("from_account_id", Long.class),
            field("to_account_id", Long.class),
            field("amount", BigDecimal.class),
            field("status", String.class),
            field("correlation_id", UUID.class),
            field("logged_at", LocalDateTime.class)
        );

        // Add all values
        for (TransferLogBatchDTO log : logs) {
            insert = insert.values(
                log.transactionId(),
                log.fromAccountId(),
                log.toAccountId(),
                log.amount(),
                log.status(),
                log.correlationId(),
                log.loggedAt()
            );
        }

        int rowsInserted = insert.execute();
        log.info("Batch inserted {} transfer logs", rowsInserted);
        return rowsInserted;
    }

    /**
     * Batch update using jOOQ batch API.
     *
     * Demonstrates:
     * - Batch UPDATE operations
     * - Parameter binding for batches
     * - Efficient bulk updates
     *
     * @param updates Account balance updates
     * @return Array of update counts
     */
    @Transactional
    public int[] batchUpdateBalances(List<BalanceUpdateDTO> updates) {
        if (updates.isEmpty()) {
            return new int[0];
        }

        List<org.jooq.Query> queries = new ArrayList<>();

        for (BalanceUpdateDTO update : updates) {
            queries.add(
                dsl.update(ACCOUNTS)
                    .set(field("balance", BigDecimal.class), update.newBalance())
                    .set(field("updated_at", LocalDateTime.class), LocalDateTime.now())
                    .where(field("id", Long.class).eq(update.accountId()))
            );
        }

        int[] results = dsl.batch(queries).execute();
        log.info("Batch updated {} account balances", results.length);
        return results;
    }

    /**
     * Bulk insert with RETURNING clause.
     *
     * Demonstrates:
     * - INSERT ... RETURNING for getting generated IDs
     * - Single round-trip for insert + retrieval
     * - PostgreSQL-specific feature via jOOQ
     *
     * @param accounts Accounts to create
     * @return List of created account IDs
     */
    @Transactional
    public List<Long> bulkInsertAccountsReturningIds(List<AccountInsertDTO> accounts) {
        if (accounts.isEmpty()) {
            return List.of();
        }

        var insert = dsl.insertInto(
            ACCOUNTS,
            field("user_id", Long.class),
            field("account_type_id", Integer.class),
            field("account_number", String.class),
            field("balance", BigDecimal.class),
            field("status", String.class)
        );

        for (AccountInsertDTO account : accounts) {
            insert = insert.values(
                account.userId(),
                account.accountTypeId(),
                account.accountNumber(),
                account.balance(),
                account.status()
            );
        }

        Result<Record1<Long>> result = insert.returningResult(field("id", Long.class)).fetch();

        List<Long> ids = result.map(r -> r.value1());
        log.info("Bulk inserted {} accounts, returned IDs: {}", ids.size(), ids);
        return ids;
    }

    // =========================================================================
    // Join Performance Demonstrations (T128)
    // =========================================================================

    /**
     * Efficient join query (single query for all data).
     *
     * Demonstrates:
     * - Proper JOIN to fetch related data
     * - No N+1 query problem
     * - Single database round-trip
     *
     * @return Accounts with user and type info
     */
    public List<AccountFullDTO> findAccountsEfficientJoin() {
        long startTime = System.currentTimeMillis();

        List<AccountFullDTO> results = dsl
            .select(
                ACCOUNTS_ID,
                field("accounts.account_number", String.class),
                ACCOUNTS_BALANCE,
                ACCOUNTS_STATUS,
                USERS_ID,
                USERS_USERNAME,
                USERS_EMAIL,
                ACCOUNT_TYPES_ID,
                ACCOUNT_TYPES_NAME,
                ACCOUNT_TYPES_MIN_BALANCE
            )
            .from(ACCOUNTS)
            .join(USERS).on(ACCOUNTS_USER_ID.eq(USERS_ID))
            .join(ACCOUNT_TYPES).on(ACCOUNTS_TYPE_ID.eq(ACCOUNT_TYPES_ID))
            .where(ACCOUNTS_STATUS.eq("ACTIVE"))
            .limit(100)
            .fetch()
            .map(r -> new AccountFullDTO(
                r.get(ACCOUNTS_ID),
                r.get(field("accounts.account_number", String.class)),
                r.get(ACCOUNTS_BALANCE),
                r.get(ACCOUNTS_STATUS),
                new UserDTO(
                    r.get(USERS_ID),
                    r.get(USERS_USERNAME),
                    r.get(USERS_EMAIL)
                ),
                new AccountTypeDTO(
                    r.get(ACCOUNT_TYPES_ID),
                    r.get(ACCOUNT_TYPES_NAME),
                    r.get(ACCOUNT_TYPES_MIN_BALANCE)
                )
            ));

        long durationMs = System.currentTimeMillis() - startTime;
        log.info("Efficient join query returned {} accounts in {} ms (1 query)", results.size(), durationMs);

        return results;
    }

    /**
     * Inefficient N+1 query simulation for comparison.
     *
     * Demonstrates:
     * - N+1 query anti-pattern
     * - Multiple database round-trips
     * - Poor performance vs JOIN
     *
     * WARNING: This is intentionally inefficient for demonstration purposes!
     *
     * @return Same data as efficient join, but slower
     */
    public List<AccountFullDTO> findAccountsInefficient() {
        long startTime = System.currentTimeMillis();
        int queryCount = 1;  // Initial account query

        // Query 1: Fetch accounts
        List<Long> accountIds = dsl
            .select(ACCOUNTS_ID)
            .from(ACCOUNTS)
            .where(ACCOUNTS_STATUS.eq("ACTIVE"))
            .limit(100)
            .fetch()
            .map(r -> r.value1());

        List<AccountFullDTO> results = new ArrayList<>();

        // N+1: Separate query for each account's related data
        for (Long accountId : accountIds) {
            // Query 2+: Fetch account with user and type (separate queries)
            var accountData = dsl
                .select(
                    ACCOUNTS_ID,
                    field("accounts.account_number", String.class),
                    ACCOUNTS_BALANCE,
                    ACCOUNTS_STATUS,
                    ACCOUNTS_USER_ID,
                    ACCOUNTS_TYPE_ID
                )
                .from(ACCOUNTS)
                .where(ACCOUNTS_ID.eq(accountId))
                .fetchOne();

            queryCount++;

            if (accountData != null) {
                Long userId = accountData.get(ACCOUNTS_USER_ID);
                Integer typeId = accountData.get(ACCOUNTS_TYPE_ID);

                // Query 3+: Fetch user
                var userData = dsl
                    .select(USERS_ID, USERS_USERNAME, USERS_EMAIL)
                    .from(USERS)
                    .where(USERS_ID.eq(userId))
                    .fetchOne();

                queryCount++;

                // Query 4+: Fetch account type
                var typeData = dsl
                    .select(ACCOUNT_TYPES_ID, ACCOUNT_TYPES_NAME, ACCOUNT_TYPES_MIN_BALANCE)
                    .from(ACCOUNT_TYPES)
                    .where(ACCOUNT_TYPES_ID.eq(typeId))
                    .fetchOne();

                queryCount++;

                if (userData != null && typeData != null) {
                    results.add(new AccountFullDTO(
                        accountData.get(ACCOUNTS_ID),
                        accountData.get(field("accounts.account_number", String.class)),
                        accountData.get(ACCOUNTS_BALANCE),
                        accountData.get(ACCOUNTS_STATUS),
                        new UserDTO(
                            userData.get(USERS_ID),
                            userData.get(USERS_USERNAME),
                            userData.get(USERS_EMAIL)
                        ),
                        new AccountTypeDTO(
                            typeData.get(ACCOUNT_TYPES_ID),
                            typeData.get(ACCOUNT_TYPES_NAME),
                            typeData.get(ACCOUNT_TYPES_MIN_BALANCE)
                        )
                    ));
                }
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;
        log.info("Inefficient N+1 query returned {} accounts in {} ms ({} queries)",
            results.size(), durationMs, queryCount);

        return results;
    }

    /**
     * Compares efficient JOIN vs inefficient N+1 queries.
     *
     * @return Performance comparison results
     */
    public JoinPerformanceComparisonDTO compareJoinPerformance() {
        // Efficient join
        long efficientStart = System.currentTimeMillis();
        List<AccountFullDTO> efficientResults = findAccountsEfficientJoin();
        long efficientDurationMs = System.currentTimeMillis() - efficientStart;

        // Small delay
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Inefficient N+1
        long inefficientStart = System.currentTimeMillis();
        List<AccountFullDTO> inefficientResults = findAccountsInefficient();
        long inefficientDurationMs = System.currentTimeMillis() - inefficientStart;

        double speedup = (double) inefficientDurationMs / efficientDurationMs;

        return new JoinPerformanceComparisonDTO(
            efficientResults.size(),
            1,  // 1 query for efficient join
            efficientDurationMs,
            inefficientResults.size(),
            1 + (inefficientResults.size() * 3),  // 1 + N*3 queries
            inefficientDurationMs,
            speedup,
            String.format("Efficient JOIN is %.1fx faster than N+1 queries", speedup)
        );
    }

    // =========================================================================
    // DTOs
    // =========================================================================

    public record AccountWithUserDTO(
        Long accountId,
        String accountNumber,
        BigDecimal balance,
        String status,
        String username,
        String email,
        String fullName
    ) {}

    public record AccountTransactionSummaryDTO(
        Long accountId,
        String accountNumber,
        BigDecimal balance,
        String username,
        String accountType,
        Integer transactionCount,
        BigDecimal totalDebited,
        BigDecimal totalCredited
    ) {}

    public record AccountRankingDTO(
        Long accountId,
        String accountNumber,
        BigDecimal balance,
        String username,
        String accountType,
        Integer rank
    ) {}

    public record HighValueAccountDTO(
        Long accountId,
        BigDecimal balance,
        String username,
        String email,
        String accountType,
        BigDecimal minBalanceRequired
    ) {}

    public record AccountSearchFilter(
        BigDecimal minBalance,
        BigDecimal maxBalance,
        String status,
        String username,
        Integer limit
    ) {}

    public record TransferLogBatchDTO(
        Long transactionId,
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        String status,
        UUID correlationId,
        LocalDateTime loggedAt
    ) {}

    public record BalanceUpdateDTO(
        Long accountId,
        BigDecimal newBalance
    ) {}

    public record AccountInsertDTO(
        Long userId,
        Integer accountTypeId,
        String accountNumber,
        BigDecimal balance,
        String status
    ) {}

    public record UserDTO(
        Long id,
        String username,
        String email
    ) {}

    public record AccountTypeDTO(
        Integer id,
        String typeName,
        BigDecimal minBalance
    ) {}

    public record AccountFullDTO(
        Long accountId,
        String accountNumber,
        BigDecimal balance,
        String status,
        UserDTO user,
        AccountTypeDTO accountType
    ) {}

    public record JoinPerformanceComparisonDTO(
        int efficientResultCount,
        int efficientQueryCount,
        long efficientDurationMs,
        int inefficientResultCount,
        int inefficientQueryCount,
        long inefficientDurationMs,
        double speedupFactor,
        String summary
    ) {
        public double efficientQueriesPerSecond() {
            return (efficientQueryCount * 1000.0) / efficientDurationMs;
        }

        public double inefficientQueriesPerSecond() {
            return (inefficientQueryCount * 1000.0) / inefficientDurationMs;
        }
    }
}
