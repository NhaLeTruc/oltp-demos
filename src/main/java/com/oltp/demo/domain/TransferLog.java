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
 * Transfer Log entity for immutable audit trail.
 *
 * This is an append-only table that demonstrates durability:
 * - All transfer operations are logged
 * - Logs survive application crashes and restarts
 * - No updates or deletes (immutable audit trail)
 * - Used to verify committed transactions after crash recovery
 *
 * Design patterns demonstrated:
 * - Write-Ahead Logging (WAL) concept
 * - Append-only data structures
 * - Audit trail for compliance and debugging
 * - High write throughput target for batch insert demos
 *
 * @see com.oltp.demo.service.acid.DurabilityDemoService
 */
@Entity
@Table(name = "transfer_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"transaction", "fromAccount", "toAccount"})
public class TransferLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Reference to the transaction this log entry is for.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    /**
     * Source account for the transfer.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account_id", nullable = false)
    private Account fromAccount;

    /**
     * Destination account for the transfer.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id", nullable = false)
    private Account toAccount;

    /**
     * Transfer amount (must be positive).
     */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /**
     * Status at time of logging: INITIATED, COMPLETED, or FAILED.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    /**
     * Correlation ID matching the transaction.
     * Enables tracing from transaction to audit log.
     */
    @Column(nullable = false, name = "correlation_id")
    private UUID correlationId;

    /**
     * Timestamp when this log entry was created.
     * Immutable (updatable = false).
     *
     * IMPORTANT: This table is append-only.
     * No @PreUpdate callback because updates should never happen.
     */
    @Column(nullable = false, updatable = false, name = "logged_at")
    private Instant loggedAt;

    /**
     * Sets loggedAt timestamp before persisting.
     */
    @jakarta.persistence.PrePersist
    protected void onCreate() {
        this.loggedAt = Instant.now();
    }

    /**
     * Transfer status enumeration for audit log.
     */
    public enum TransferStatus {
        /**
         * Transfer has been initiated (atomicity demo: before commit).
         */
        INITIATED,

        /**
         * Transfer completed successfully (durability demo: committed).
         */
        COMPLETED,

        /**
         * Transfer failed (consistency demo: constraint violation).
         */
        FAILED
    }
}
