package com.oltp.demo.controller;

import com.oltp.demo.domain.Account;
import com.oltp.demo.service.AccountService;
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for account management operations.
 * Provides basic CRUD endpoints for demonstration setup.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * Create a new account.
     * POST /api/accounts
     *
     * @param request Account creation request
     * @return Created account
     */
    @PostMapping
    @Timed(value = "api.accounts.create", description = "Time taken to create an account")
    public ResponseEntity<Map<String, Object>> createAccount(
            @RequestBody CreateAccountRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId
    ) {
        // Set correlation ID
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        MDC.put("correlationId", corrId);

        log.info("POST /api/accounts: userId={}, accountTypeId={}, initialBalance={}",
                request.userId, request.accountTypeId, request.initialBalance);

        try {
            Account account = accountService.createAccount(
                    request.userId,
                    request.accountTypeId,
                    request.initialBalance,
                    request.accountNumber
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("account", toAccountResponse(account));
            response.put("correlationId", corrId);

            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            log.error("Invalid request: {}", e.getMessage());

            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "ValidationError");
            error.put("message", e.getMessage());
            error.put("correlationId", corrId);

            return ResponseEntity.badRequest().body(error);

        } finally {
            MDC.remove("correlationId");
        }
    }

    /**
     * Get account by ID.
     * GET /api/accounts/{accountId}
     *
     * @param accountId Account ID
     * @return Account details
     */
    @GetMapping("/{accountId}")
    @Timed(value = "api.accounts.get", description = "Time taken to get an account")
    public ResponseEntity<Map<String, Object>> getAccount(
            @PathVariable Long accountId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId
    ) {
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        MDC.put("correlationId", corrId);

        log.info("GET /api/accounts/{}: accountId={}", accountId, accountId);

        try {
            Account account = accountService.getAccount(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("account", toAccountResponse(account));
            response.put("correlationId", corrId);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.error("Account not found: {}", accountId);

            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "NotFound");
            error.put("message", e.getMessage());
            error.put("correlationId", corrId);

            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);

        } finally {
            MDC.remove("correlationId");
        }
    }

    /**
     * List accounts with optional filtering.
     * GET /api/accounts
     *
     * @param userId Optional user ID filter
     * @param accountTypeId Optional account type ID filter
     * @param status Optional status filter
     * @param page Page number (0-indexed)
     * @param size Page size
     * @param sort Sort field
     * @return Page of accounts
     */
    @GetMapping
    @Timed(value = "api.accounts.list", description = "Time taken to list accounts")
    public ResponseEntity<Map<String, Object>> listAccounts(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long accountTypeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sort,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId
    ) {
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        MDC.put("correlationId", corrId);

        log.info("GET /api/accounts: userId={}, accountTypeId={}, status={}, page={}, size={}",
                userId, accountTypeId, status, page, size);

        try {
            // Validate pagination parameters
            if (page < 0) {
                throw new IllegalArgumentException("Page number must be >= 0");
            }
            if (size < 1 || size > 100) {
                throw new IllegalArgumentException("Page size must be between 1 and 100");
            }

            // Create pageable
            PageRequest pageable = PageRequest.of(page, size, Sort.by(sort).descending());

            // Get accounts
            Page<Account> accountsPage = accountService.listAccounts(
                    userId,
                    accountTypeId,
                    status,
                    pageable
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("accounts", accountsPage.getContent().stream()
                    .map(this::toAccountResponse)
                    .toList());
            response.put("pagination", Map.of(
                    "page", accountsPage.getNumber(),
                    "size", accountsPage.getSize(),
                    "totalElements", accountsPage.getTotalElements(),
                    "totalPages", accountsPage.getTotalPages(),
                    "first", accountsPage.isFirst(),
                    "last", accountsPage.isLast()
            ));
            response.put("correlationId", corrId);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.error("Invalid request: {}", e.getMessage());

            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "ValidationError");
            error.put("message", e.getMessage());
            error.put("correlationId", corrId);

            return ResponseEntity.badRequest().body(error);

        } finally {
            MDC.remove("correlationId");
        }
    }

    /**
     * Update account status.
     * PATCH /api/accounts/{accountId}/status
     *
     * @param accountId Account ID
     * @param request Status update request
     * @return Updated account
     */
    @PatchMapping("/{accountId}/status")
    @Timed(value = "api.accounts.update_status", description = "Time taken to update account status")
    public ResponseEntity<Map<String, Object>> updateAccountStatus(
            @PathVariable Long accountId,
            @RequestBody UpdateStatusRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId
    ) {
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        MDC.put("correlationId", corrId);

        log.info("PATCH /api/accounts/{}/status: status={}", accountId, request.status);

        try {
            Account account = accountService.updateAccountStatus(accountId, request.status);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("account", toAccountResponse(account));
            response.put("correlationId", corrId);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            log.error("Update failed: {}", e.getMessage());

            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", "ValidationError");
            error.put("message", e.getMessage());
            error.put("correlationId", corrId);

            return ResponseEntity.badRequest().body(error);

        } finally {
            MDC.remove("correlationId");
        }
    }

    /**
     * Convert Account entity to response DTO.
     *
     * @param account Account entity
     * @return Response map
     */
    private Map<String, Object> toAccountResponse(Account account) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", account.getId());
        response.put("userId", account.getUser().getId());
        response.put("accountTypeId", account.getAccountType().getId());
        response.put("accountTypeName", account.getAccountType().getName());
        response.put("accountNumber", account.getAccountNumber());
        response.put("balance", account.getBalance());
        response.put("status", account.getStatus());
        response.put("version", account.getVersion());
        response.put("createdAt", account.getCreatedAt());
        response.put("updatedAt", account.getUpdatedAt());
        return response;
    }

    /**
     * Request DTO for creating an account.
     */
    public static class CreateAccountRequest {
        public Long userId;
        public Long accountTypeId;
        public BigDecimal initialBalance;
        public String accountNumber;
    }

    /**
     * Request DTO for updating account status.
     */
    public static class UpdateStatusRequest {
        public String status;
    }
}
