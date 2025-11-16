package com.oltp.demo.controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oltp.demo.service.acid.AtomicityDemoService;
import com.oltp.demo.service.acid.ConsistencyDemoService;
import com.oltp.demo.service.acid.DurabilityDemoService;
import com.oltp.demo.service.acid.IsolationDemoService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for ACID property demonstrations.
 *
 * Provides endpoints to demonstrate all four ACID properties:
 * - Atomicity: All-or-nothing transactions
 * - Consistency: Constraint enforcement
 * - Isolation: Concurrent transaction handling
 * - Durability: Persistence guarantees
 *
 * All endpoints return JSON responses with demonstration results.
 * Correlation IDs are automatically added to responses for tracing.
 *
 * @see <a href="specs/001-oltp-core-demo/contracts/openapi.yaml">API Specification</a>
 * @see <a href="specs/001-oltp-core-demo/spec.md">US1: ACID Transaction Guarantees</a>
 */
@Slf4j
@RestController
@RequestMapping("/api/demos/acid")
@RequiredArgsConstructor
@Tag(name = "ACID Demonstrations", description = "Demonstrations of ACID transaction properties")
public class AcidDemoController {

    private final AtomicityDemoService atomicityService;
    private final ConsistencyDemoService consistencyService;
    private final IsolationDemoService isolationService;
    private final DurabilityDemoService durabilityService;

    // =========================================================================
    // Atomicity Demonstrations
    // =========================================================================

    @PostMapping("/atomicity/transfer")
    @Operation(summary = "Demonstrate atomic transfer",
               description = "Executes a money transfer that either completes fully or rolls back completely")
    public ResponseEntity<AtomicityDemoService.TransferResult> demonstrateAtomicTransfer(
            @RequestBody TransferRequest request) {

        log.info("API: Atomicity transfer demo - from={}, to={}, amount={}",
                request.fromAccountId, request.toAccountId, request.amount);

        try {
            AtomicityDemoService.TransferResult result =
                atomicityService.successfulTransfer(
                    request.fromAccountId,
                    request.toAccountId,
                    request.amount
                );

            return ResponseEntity.ok(result);

        } catch (AtomicityDemoService.InsufficientFundsException e) {
            log.warn("Transfer failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(AtomicityDemoService.TransferResult.failure(e.getMessage()));
        }
    }

    @PostMapping("/atomicity/rollback")
    @Operation(summary = "Demonstrate transaction rollback",
               description = "Attempts transfer with insufficient funds to demonstrate complete rollback")
    public ResponseEntity<AtomicityDemoService.TransferResult> demonstrateRollback(
            @RequestBody TransferRequest request) {

        log.info("API: Atomicity rollback demo - from={}, to={}, amount={}",
                request.fromAccountId, request.toAccountId, request.amount);

        try {
            AtomicityDemoService.TransferResult result =
                atomicityService.failedTransferInsufficientFunds(
                    request.fromAccountId,
                    request.toAccountId,
                    request.amount
                );

            return ResponseEntity.ok(result);

        } catch (AtomicityDemoService.InsufficientFundsException e) {
            log.info("Rollback demonstrated successfully: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(AtomicityDemoService.TransferResult.failure(
                    "Transaction rolled back: " + e.getMessage()
                ));
        }
    }

    @PostMapping("/atomicity/mid-transaction-failure")
    @Operation(summary = "Demonstrate mid-transaction rollback",
               description = "Simulates failure after debit to show automatic rollback")
    public ResponseEntity<AtomicityDemoService.TransferResult> demonstrateMidTransactionFailure(
            @RequestBody MidTransactionFailureRequest request) {

        log.info("API: Mid-transaction failure demo - from={}, to={}, simulate={}",
                request.fromAccountId, request.toAccountId, request.simulateFailure);

        try {
            AtomicityDemoService.TransferResult result =
                atomicityService.transferWithMidTransactionFailure(
                    request.fromAccountId,
                    request.toAccountId,
                    request.amount,
                    request.simulateFailure
                );

            return ResponseEntity.ok(result);

        } catch (RuntimeException e) {
            log.info("Mid-transaction rollback demonstrated: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(AtomicityDemoService.TransferResult.failure(
                    "Transaction rolled back after failure: " + e.getMessage()
                ));
        }
    }

    // =========================================================================
    // Consistency Demonstrations
    // =========================================================================

    @PostMapping("/consistency/negative-balance")
    @Operation(summary = "Demonstrate negative balance constraint",
               description = "Attempts to create negative balance to show constraint enforcement")
    public ResponseEntity<ConsistencyDemoService.ConstraintViolationResult> demonstrateNegativeBalanceConstraint(
            @RequestBody ConstraintTestRequest request) {

        log.info("API: Negative balance constraint demo - account={}, withdrawal={}",
                request.accountId, request.amount);

        ConsistencyDemoService.ConstraintViolationResult result =
            consistencyService.demonstrateNegativeBalanceConstraint(
                request.accountId,
                request.amount
            );

        return ResponseEntity.ok(result);
    }

    @PostMapping("/consistency/foreign-key")
    @Operation(summary = "Demonstrate foreign key constraint",
               description = "Attempts to create account for non-existent user")
    public ResponseEntity<ConsistencyDemoService.ConstraintViolationResult> demonstrateForeignKeyConstraint(
            @RequestParam Long nonExistentUserId,
            @RequestParam Integer accountTypeId) {

        log.info("API: Foreign key constraint demo - userId={}, typeId={}",
                nonExistentUserId, accountTypeId);

        ConsistencyDemoService.ConstraintViolationResult result =
            consistencyService.demonstrateForeignKeyConstraint(
                nonExistentUserId,
                accountTypeId
            );

        return ResponseEntity.ok(result);
    }

    @PostMapping("/consistency/minimum-balance")
    @Operation(summary = "Demonstrate minimum balance constraint",
               description = "Attempts to reduce balance below account type minimum")
    public ResponseEntity<ConsistencyDemoService.ConstraintViolationResult> demonstrateMinimumBalanceConstraint(
            @RequestBody MinimumBalanceRequest request) {

        log.info("API: Minimum balance constraint demo - account={}, target={}",
                request.accountId, request.targetBalance);

        ConsistencyDemoService.ConstraintViolationResult result =
            consistencyService.demonstrateMinimumBalanceConstraint(
                request.accountId,
                request.targetBalance
            );

        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // Isolation Demonstrations
    // =========================================================================

    @GetMapping("/isolation/read-committed")
    @Operation(summary = "Demonstrate READ_COMMITTED isolation",
               description = "Shows default isolation level behavior")
    public ResponseEntity<IsolationDemoService.IsolationDemoResult> demonstrateReadCommitted(
            @RequestParam Long accountId) {

        log.info("API: READ_COMMITTED isolation demo - account={}", accountId);

        IsolationDemoService.IsolationDemoResult result =
            isolationService.demonstrateReadCommitted(accountId);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/isolation/repeatable-read")
    @Operation(summary = "Demonstrate REPEATABLE_READ isolation",
               description = "Shows snapshot isolation behavior")
    public ResponseEntity<IsolationDemoService.IsolationDemoResult> demonstrateRepeatableRead(
            @RequestParam Long accountId) {

        log.info("API: REPEATABLE_READ isolation demo - account={}", accountId);

        IsolationDemoService.IsolationDemoResult result =
            isolationService.demonstrateRepeatableRead(accountId);

        return ResponseEntity.ok(result);
    }

    @PostMapping("/isolation/serializable")
    @Operation(summary = "Demonstrate SERIALIZABLE isolation",
               description = "Shows strictest isolation level")
    public ResponseEntity<IsolationDemoService.IsolationDemoResult> demonstrateSerializable(
            @RequestBody SerializableRequest request) {

        log.info("API: SERIALIZABLE isolation demo - account={}, amount={}",
                request.accountId, request.amount);

        IsolationDemoService.IsolationDemoResult result =
            isolationService.demonstrateSerializable(request.accountId, request.amount);

        return ResponseEntity.ok(result);
    }

    @PostMapping("/isolation/concurrent-transfers")
    @Operation(summary = "Execute concurrent transfers",
               description = "Runs multiple concurrent operations to demonstrate isolation")
    public ResponseEntity<IsolationDemoService.ConcurrentTransferResult> demonstrateConcurrentTransfers(
            @RequestBody ConcurrentTransfersRequest request) {

        log.info("API: Concurrent transfers demo - account={}, amount={}, count={}",
                request.accountId, request.amount, request.concurrentCount);

        IsolationDemoService.ConcurrentTransferResult result =
            isolationService.executeConcurrentTransfers(
                request.accountId,
                request.amount,
                request.concurrentCount
            );

        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // Durability Demonstrations
    // =========================================================================

    @PostMapping("/durability/commit")
    @Operation(summary = "Demonstrate durable commit",
               description = "Creates a committed transaction that survives crashes")
    public ResponseEntity<DurabilityDemoService.DurabilityResult> demonstrateDurableCommit() {
        UUID correlationId = UUID.randomUUID();

        log.info("API: Durable commit demo - correlationId={}", correlationId);

        DurabilityDemoService.DurabilityResult result =
            durabilityService.demonstrateDurableCommit(correlationId);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/durability/verify-recovery")
    @Operation(summary = "Verify crash recovery",
               description = "Checks that committed transaction survived restart")
    public ResponseEntity<DurabilityDemoService.RecoveryVerificationResult> verifyCrashRecovery(
            @RequestParam UUID correlationId) {

        log.info("API: Crash recovery verification - correlationId={}", correlationId);

        DurabilityDemoService.RecoveryVerificationResult result =
            durabilityService.verifyCrashRecovery(correlationId);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/durability/recent-transactions")
    @Operation(summary = "Get recent completed transactions",
               description = "Retrieves completed transactions from last N minutes")
    public ResponseEntity<List<DurabilityDemoService.CompletedTransactionInfo>> getRecentCompletedTransactions(
            @RequestParam(defaultValue = "60") int minutes) {

        log.info("API: Recent completed transactions - minutes={}", minutes);

        List<DurabilityDemoService.CompletedTransactionInfo> result =
            durabilityService.getRecentCompletedTransactions(minutes);

        return ResponseEntity.ok(result);
    }

    @GetMapping("/durability/audit-trail")
    @Operation(summary = "Get transfer audit trail",
               description = "Retrieves immutable audit trail for a transfer")
    public ResponseEntity<List<DurabilityDemoService.TransferLogInfo>> getAuditTrail(
            @RequestParam UUID correlationId) {

        log.info("API: Audit trail query - correlationId={}", correlationId);

        List<DurabilityDemoService.TransferLogInfo> result =
            durabilityService.getTransferAuditTrail(correlationId);

        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // Request DTOs
    // =========================================================================

    public record TransferRequest(
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount
    ) {}

    public record MidTransactionFailureRequest(
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        boolean simulateFailure
    ) {}

    public record ConstraintTestRequest(
        Long accountId,
        BigDecimal amount
    ) {}

    public record MinimumBalanceRequest(
        Long accountId,
        BigDecimal targetBalance
    ) {}

    public record SerializableRequest(
        Long accountId,
        BigDecimal amount
    ) {}

    public record ConcurrentTransfersRequest(
        Long accountId,
        BigDecimal amount,
        int concurrentCount
    ) {}
}
