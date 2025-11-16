package com.oltp.demo.controller;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oltp.demo.service.concurrency.DeadlockDemoService;
import com.oltp.demo.service.concurrency.IsolationLevelService;
import com.oltp.demo.service.concurrency.OptimisticLockingService;
import com.oltp.demo.service.concurrency.PessimisticLockingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for Concurrency Control demonstrations.
 *
 * Provides endpoints to demonstrate:
 * - Optimistic locking with version checking and retries
 * - Pessimistic locking with SELECT FOR UPDATE
 * - Deadlock detection and recovery
 * - Lock performance comparison
 *
 * All endpoints return JSON responses with detailed statistics including:
 * - Success/failure counts
 * - Retry statistics
 * - Lock wait times
 * - Deadlock occurrences
 * - Performance metrics
 *
 * @see <a href="specs/001-oltp-core-demo/contracts/openapi.yaml">API Specification</a>
 * @see <a href="specs/001-oltp-core-demo/spec.md">US2: Concurrency and Conflict Handling</a>
 */
@Slf4j
@RestController
@RequestMapping("/api/demos/concurrency")
@RequiredArgsConstructor
@Tag(name = "Concurrency Demonstrations", description = "Demonstrations of concurrency control mechanisms")
public class ConcurrencyDemoController {

    private final OptimisticLockingService optimisticLockingService;
    private final PessimisticLockingService pessimisticLockingService;
    private final DeadlockDemoService deadlockDemoService;
    private final IsolationLevelService isolationLevelService;

    // =========================================================================
    // Optimistic Locking Demonstrations
    // =========================================================================

    @PostMapping("/optimistic-locking")
    @Operation(summary = "Demonstrate optimistic locking",
               description = "Executes concurrent updates using optimistic locking with @Version and retry logic")
    public ResponseEntity<OptimisticLockingService.OptimisticLockingResult> demonstrateOptimisticLocking(
            @RequestBody ConcurrentOperationsRequest request) {

        log.info("API: Optimistic locking demo - account={}, amount={}, operations={}",
                request.accountId, request.amount, request.concurrentOperations);

        try {
            OptimisticLockingService.OptimisticLockingResult result =
                optimisticLockingService.executeConcurrentUpdates(
                    request.accountId,
                    request.amount,
                    request.concurrentOperations
                );

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Optimistic locking demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/optimistic-locking/single-update")
    @Operation(summary = "Single update with version check",
               description = "Demonstrates a single optimistic locking update with version tracking")
    public ResponseEntity<OptimisticLockingService.VersionedUpdateResult> demonstrateSingleOptimisticUpdate(
            @RequestBody SingleUpdateRequest request) {

        log.info("API: Single optimistic update - account={}, amount={}",
                request.accountId, request.amount);

        try {
            OptimisticLockingService.VersionedUpdateResult result =
                optimisticLockingService.updateWithVersionCheck(
                    request.accountId,
                    request.amount
                );

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Single optimistic update failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new OptimisticLockingService.VersionedUpdateResult(
                    false, null, null, null, null,
                    "Update failed: " + e.getMessage()
                ));
        }
    }

    // =========================================================================
    // Pessimistic Locking Demonstrations
    // =========================================================================

    @PostMapping("/pessimistic-locking")
    @Operation(summary = "Demonstrate pessimistic locking",
               description = "Executes concurrent updates using pessimistic locking (SELECT FOR UPDATE)")
    public ResponseEntity<PessimisticLockingService.PessimisticLockingResult> demonstratePessimisticLocking(
            @RequestBody ConcurrentOperationsRequest request) {

        log.info("API: Pessimistic locking demo - account={}, amount={}, operations={}",
                request.accountId, request.amount, request.concurrentOperations);

        try {
            PessimisticLockingService.PessimisticLockingResult result =
                pessimisticLockingService.executeConcurrentUpdates(
                    request.accountId,
                    request.amount,
                    request.concurrentOperations
                );

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Pessimistic locking demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // =========================================================================
    // Deadlock Demonstrations
    // =========================================================================

    @PostMapping("/deadlock")
    @Operation(summary = "Demonstrate deadlock detection and recovery",
               description = "Creates bidirectional transfers to trigger deadlock, demonstrates automatic recovery")
    public ResponseEntity<DeadlockDemoService.DeadlockResult> demonstrateDeadlock(
            @RequestBody DeadlockRequest request) {

        log.info("API: Deadlock demo - accountA={}, accountB={}, amount={}",
                request.accountIdA, request.accountIdB, request.amount);

        try {
            DeadlockDemoService.DeadlockResult result =
                deadlockDemoService.demonstrateBidirectionalTransferDeadlock(
                    request.accountIdA,
                    request.accountIdB,
                    request.amount
                );

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Deadlock demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/deadlock/prevention")
    @Operation(summary = "Demonstrate deadlock prevention",
               description = "Shows how lock ordering prevents deadlocks")
    public ResponseEntity<DeadlockPreventionResult> demonstrateDeadlockPrevention(
            @RequestBody DeadlockRequest request) {

        log.info("API: Deadlock prevention demo - accountA={}, accountB={}, amount={}",
                request.accountIdA, request.accountIdB, request.amount);

        try {
            // Execute bidirectional transfers with lock ordering
            boolean transfer1Success = deadlockDemoService.transferWithDeadlockPrevention(
                request.accountIdA,
                request.accountIdB,
                request.amount,
                true  // A → B
            );

            boolean transfer2Success = deadlockDemoService.transferWithDeadlockPrevention(
                request.accountIdA,
                request.accountIdB,
                request.amount,
                false  // B → A
            );

            return ResponseEntity.ok(new DeadlockPreventionResult(
                transfer1Success && transfer2Success,
                0,  // No deadlocks with prevention
                "Lock ordering prevents deadlocks"
            ));

        } catch (Exception e) {
            log.error("Deadlock prevention demo failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new DeadlockPreventionResult(
                    false,
                    0,
                    "Failed: " + e.getMessage()
                ));
        }
    }

    // =========================================================================
    // Isolation Level Demonstrations
    // =========================================================================

    @PostMapping("/isolation-levels/dirty-read")
    @Operation(summary = "Demonstrate dirty read phenomenon",
               description = "Shows READ_UNCOMMITTED isolation level behavior (PostgreSQL treats as READ_COMMITTED)")
    public ResponseEntity<IsolationLevelService.DirtyReadResult> demonstrateDirtyRead(
            @RequestBody IsolationLevelRequest request) {

        log.info("API: Dirty read demo - account={}, tempAmount={}",
                request.accountId, request.amount);

        try {
            IsolationLevelService.DirtyReadResult result =
                isolationLevelService.demonstrateDirtyRead(
                    request.accountId,
                    request.amount
                );

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Dirty read demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/isolation-levels/non-repeatable-read")
    @Operation(summary = "Demonstrate non-repeatable read phenomenon",
               description = "Shows READ_COMMITTED isolation level behavior with concurrent updates")
    public ResponseEntity<IsolationLevelService.NonRepeatableReadResult> demonstrateNonRepeatableRead(
            @RequestBody IsolationLevelRequest request) {

        log.info("API: Non-repeatable read demo - account={}, updateAmount={}",
                request.accountId, request.amount);

        try {
            IsolationLevelService.NonRepeatableReadResult result =
                isolationLevelService.demonstrateNonRepeatableRead(
                    request.accountId,
                    request.amount
                );

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Non-repeatable read demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/isolation-levels/phantom-read")
    @Operation(summary = "Demonstrate phantom read phenomenon",
               description = "Shows REPEATABLE_READ isolation level behavior (PostgreSQL prevents phantom reads)")
    public ResponseEntity<IsolationLevelService.PhantomReadResult> demonstratePhantomRead(
            @RequestBody PhantomReadRequest request) {

        log.info("API: Phantom read demo - minBalance={}, newAccountBalance={}",
                request.minBalance, request.newAccountBalance);

        try {
            IsolationLevelService.PhantomReadResult result =
                isolationLevelService.demonstratePhantomRead(
                    request.minBalance,
                    request.newAccountBalance
                );

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Phantom read demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/isolation-levels/serializable")
    @Operation(summary = "Demonstrate SERIALIZABLE isolation",
               description = "Shows strictest isolation level with full consistency guarantees")
    public ResponseEntity<IsolationLevelService.SerializableIsolationResult> demonstrateSerializable(
            @RequestBody IsolationLevelRequest request) {

        log.info("API: Serializable isolation demo - account={}, amount={}",
                request.accountId, request.amount);

        try {
            IsolationLevelService.SerializableIsolationResult result =
                isolationLevelService.demonstrateSerializable(
                    request.accountId,
                    request.amount
                );

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Serializable isolation demo failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // =========================================================================
    // Request DTOs
    // =========================================================================

    public record ConcurrentOperationsRequest(
        Long accountId,
        BigDecimal amount,
        int concurrentOperations
    ) {}

    public record SingleUpdateRequest(
        Long accountId,
        BigDecimal amount
    ) {}

    public record DeadlockRequest(
        Long accountIdA,
        Long accountIdB,
        BigDecimal amount
    ) {}

    public record DeadlockPreventionResult(
        boolean success,
        int deadlocksDetected,
        String message
    ) {}

    public record IsolationLevelRequest(
        Long accountId,
        BigDecimal amount
    ) {}

    public record PhantomReadRequest(
        BigDecimal minBalance,
        BigDecimal newAccountBalance
    ) {}
}
