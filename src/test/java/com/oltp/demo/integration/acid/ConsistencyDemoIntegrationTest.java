package com.oltp.demo.integration.acid;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.oltp.demo.integration.BaseIntegrationTest;
import com.oltp.demo.service.acid.ConsistencyDemoService;

/**
 * Integration tests for Consistency demonstrations.
 *
 * Tests verify that:
 * - Database CHECK constraints are enforced
 * - Foreign key constraints prevent orphaned records
 * - Minimum balance rules are respected
 * - Unique constraints prevent duplicates
 *
 * Uses real PostgreSQL to test actual constraint enforcement.
 */
@SpringBootTest
class ConsistencyDemoIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ConsistencyDemoService consistencyService;

    @Test
    void demonstrateNegativeBalanceConstraint_ShouldEnforceConstraint() {
        // Given
        Long accountId = 1L;
        BigDecimal excessiveWithdrawal = BigDecimal.valueOf(999999.00);

        // When
        ConsistencyDemoService.ConstraintViolationResult result =
            consistencyService.demonstrateNegativeBalanceConstraint(accountId, excessiveWithdrawal);

        // Then
        assertThat(result.constraintEnforced()).isTrue();
        assertThat(result.constraintName())
            .isNotNull()
            .satisfiesAnyOf(
                name -> assertThat(name).contains("chk_accounts_balance_non_negative"),
                name -> assertThat(name).contains("Account.debit()")
            );
        assertThat(result.message()).containsAnyOf("constraint", "validation", "enforced");
    }

    @Test
    void demonstrateForeignKeyConstraint_ShouldEnforceConstraint() {
        // Given
        Long nonExistentUserId = 999999L;
        Integer validAccountTypeId = 1;

        // When
        ConsistencyDemoService.ConstraintViolationResult result =
            consistencyService.demonstrateForeignKeyConstraint(nonExistentUserId, validAccountTypeId);

        // Then
        assertThat(result.constraintEnforced()).isTrue();
        assertThat(result.constraintName()).isEqualTo("fk_accounts_user");
        assertThat(result.message()).contains("Foreign key constraint enforced");
    }

    @Test
    void demonstrateMinimumBalanceConstraint_ShouldEnforceConstraint() {
        // Given - Account 2 is a SAVINGS account with $100 minimum balance
        Long savingsAccountId = 2L;
        BigDecimal belowMinimum = BigDecimal.valueOf(50.00);

        // When
        ConsistencyDemoService.ConstraintViolationResult result =
            consistencyService.demonstrateMinimumBalanceConstraint(savingsAccountId, belowMinimum);

        // Then
        assertThat(result.constraintEnforced()).isTrue();
        assertThat(result.constraintName()).isEqualTo("chk_accounts_balance_respects_minimum");
        assertThat(result.message()).contains("Minimum balance constraint enforced");
    }

    @Test
    void demonstrateUniqueConstraint_ShouldEnforceConstraint() {
        // Given - "alice" username exists from seed data
        String existingUsername = "alice";

        // When
        ConsistencyDemoService.ConstraintViolationResult result =
            consistencyService.demonstrateUniqueConstraint(existingUsername);

        // Then
        assertThat(result.constraintEnforced()).isTrue();
        assertThat(result.constraintName()).contains("UNIQUE");
        assertThat(result.message()).contains("Unique constraint enforced");
    }

    @Test
    void demonstrateValidOperation_ShouldSucceed() {
        // Given
        Long accountId = 1L;
        BigDecimal validAmount = BigDecimal.valueOf(10.00);

        // When
        ConsistencyDemoService.ConstraintViolationResult result =
            consistencyService.demonstrateValidOperation(accountId, validAmount);

        // Then
        assertThat(result.constraintEnforced()).isTrue();
        assertThat(result.message()).contains("Valid operation succeeded");
    }
}
