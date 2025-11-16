package com.oltp.demo.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Transaction entity representing business transactions.
 *
 * Supports three types of transactions:
 * - TRANSFER: from_account_id and to_account_id both present
 * - DEPOSIT: only to_account_id present (from_account_id is null)
 * - WITHDRAWAL: only from_account_id present (to_account_id is null)
 *
 * Key features:
 * - correlation_id (UUID) for distributed tracing across logs, metrics, traces
 * - Status tracking: PENDING → COMPLETED / FAILED / ROLLED_BACK
 * - Nullable account fields to support deposits and withdrawals
 * - Complex CHECK constraints enforce business rules at database level
 *
 * This is the central entity for ACID demonstrations:
 * - Atomicity: Transaction completes fully or rolls back completely
 * - Consistency: CHECK constraints enforce business rules
 * - Isolation: Concurrent transactions don't interfere
 * - Durability: Committed transactions are persisted
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"fromAccount", "toAccount"})
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Source account for transfers and withdrawals.
     * NULL for deposits.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account_id")
    private Account fromAccount;

    /**
     * Destination account for transfers and deposits.
     * NULL for withdrawals.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id")
    private Account toAccount;

    /**
     * Transaction amount (must be positive).
     */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /**
     * Type of transaction: TRANSFER, DEPOSIT, or WITHDRAWAL.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, name = "transaction_type", length = 20)
    private TransactionType transactionType;

    /**
     * Transaction status lifecycle:
     * PENDING → COMPLETED (success)
     * PENDING → FAILED (business logic failure)
     * PENDING → ROLLED_BACK (atomicity demonstration)
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.PENDING;

    /**
     * Correlation ID for distributed tracing.
     *
     * This UUID is propagated across:
     * - Application logs (via MDC)
     * - Metrics tags
     * - Distributed traces (OpenTelemetry)
     * - Audit logs (transfer_logs table)
     *
     * Enables end-to-end request tracking for debugging and observability.
     */
    @Column(nullable = false, name = "correlation_id")
    private UUID correlationId;

    /**
     * Error message if transaction failed.
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Timestamp when transaction was created.
     * Immutable (updatable = false).
     */
    @Column(nullable = false, updatable = false, name = "created_at")
    private Instant createdAt;

    /**
     * Timestamp when transaction completed (success or failure).
     * NULL while status is PENDING.
     */
    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Sets timestamps and generates correlation ID before persisting.
     */
    @jakarta.persistence.PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        if (this.correlationId == null) {
            this.correlationId = UUID.randomUUID();
        }
    }

    /**
     * Transaction type enumeration.
     */
    public enum TransactionType {
        TRANSFER,
        DEPOSIT,
        WITHDRAWAL
    }

    /**
     * Transaction status enumeration.
     */
    public enum TransactionStatus {
        PENDING,
        COMPLETED,
        FAILED,
        ROLLED_BACK
    }

    /**
     * Marks transaction as completed.
     */
    public void markCompleted() {
        this.status = TransactionStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /**
     * Marks transaction as failed with an error message.
     *
     * @param errorMessage the error message
     */
    public void markFailed(String errorMessage) {
        this.status = TransactionStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = Instant.now();
    }

    /**
     * Marks transaction as rolled back (atomicity demonstration).
     */
    public void markRolledBack() {
        this.status = TransactionStatus.ROLLED_BACK;
        this.completedAt = Instant.now();
    }
}
