package com.example.paymentcore.ledger;

import com.example.paymentcore.domain.LedgerSide;
import com.example.paymentcore.domain.OwnerScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** 账户提供者：账户身份固定账套、归属、币种和正常方向，不允许由请求参数随意切换。 */
@Service
public class LedgerAccountService {

    public record AccountRecord(String accountId, String bookId, String ownerScope, String merchantId,
                                String currency, String category, String normalSide, boolean allowOverdraft) {
    }

    private final JdbcTemplate jdbc;

    public LedgerAccountService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String accountId(String bookId, OwnerScope scope, String merchantId, String currency, String category) {
        return bookId + "|" + scope.name() + "|" + (merchantId == null ? "PLATFORM" : merchantId)
                + "|" + currency + "|" + category;
    }

    public AccountRecord account(String accountId) {
        List<AccountRecord> rows = jdbc.query(
                "SELECT account_id, book_id, owner_scope, merchant_id, currency, category, normal_side, "
                        + "allow_overdraft FROM ledger_account WHERE account_id = ?",
                (rs, i) -> new AccountRecord(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getBoolean(8)),
                accountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public AccountRecord ensureAccount(String bookId, OwnerScope scope, String merchantId, String currency,
                                       String category, LedgerSide normalSide, boolean allowOverdraft) {
        String accountId = accountId(bookId, scope, merchantId, currency, category);
        AccountRecord existing = account(accountId);
        if (existing != null) {
            return existing;
        }
        try {
            jdbc.update("INSERT INTO ledger_account(account_id, book_id, owner_scope, merchant_id, currency, "
                            + "category, normal_side, allow_overdraft) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    accountId, bookId, scope.name(), merchantId, currency, category, normalSide.name(),
                    allowOverdraft);
            jdbc.update("INSERT INTO account_balance(account_id, posted_balance, reserved_total, version) "
                    + "VALUES (?, 0, 0, 0)", accountId);
        } catch (DuplicateKeyException e) {
            // 并发建账户由主键裁决，随后读取既有账户
        }
        AccountRecord created = account(accountId);
        if (created == null) {
            throw new IllegalStateException("账户创建后无法读取: " + accountId);
        }
        return created;
    }

    public String merchantPayable(String bookId, String merchantId, String currency) {
        return ensureAccount(bookId, OwnerScope.MERCHANT, merchantId, currency,
                LedgerConstants.MERCHANT_PAYABLE, LedgerSide.CREDIT, true).accountId();
    }

    public String receivableChannel(String bookId, String currency) {
        return ensureAccount(bookId, OwnerScope.PLATFORM, null, currency,
                LedgerConstants.RECEIVABLE_CHANNEL, LedgerSide.DEBIT, true).accountId();
    }

    public String feeIncome(String bookId, String currency) {
        return ensureAccount(bookId, OwnerScope.PLATFORM, null, currency,
                LedgerConstants.FEE_INCOME, LedgerSide.CREDIT, false).accountId();
    }

    public String bank(String bookId, String currency) {
        return ensureAccount(bookId, OwnerScope.PLATFORM, null, currency,
                LedgerConstants.BANK, LedgerSide.DEBIT, true).accountId();
    }
}
