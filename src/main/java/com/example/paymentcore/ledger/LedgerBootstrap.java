package com.example.paymentcore.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 启动时准备最小账套、币种元数据与平台控制账户；不创建商户账户。 */
@Component
public class LedgerBootstrap implements org.springframework.boot.ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final LedgerAccountService accounts;

    public LedgerBootstrap(JdbcTemplate jdbc, LedgerAccountService accounts) {
        this.jdbc = jdbc;
        this.accounts = accounts;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        ensureBook();
        seedCurrency("CNY", 2);
        seedCurrency("USD", 2);
        seedCurrency("JPY", 0);
        seedCurrency("KWD", 3);
        accounts.receivableChannel(LedgerConstants.BOOK_ID, LedgerConstants.DEFAULT_CURRENCY);
        accounts.feeIncome(LedgerConstants.BOOK_ID, LedgerConstants.DEFAULT_CURRENCY);
        accounts.bank(LedgerConstants.BOOK_ID, LedgerConstants.DEFAULT_CURRENCY);
    }

    private void ensureBook() {
        List<String> rows = jdbc.queryForList("SELECT book_id FROM ledger_book WHERE book_id = ?",
                String.class, LedgerConstants.BOOK_ID);
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO ledger_book(book_id, legal_entity_id, policy_version, status) "
                            + "VALUES (?, ?, ?, ?)",
                    LedgerConstants.BOOK_ID, "PLATFORM-LTD", LedgerConstants.POLICY_VERSION, "OPEN");
        }
    }

    private void seedCurrency(String currency, int exponent) {
        jdbc.update("MERGE INTO currency_metadata KEY(currency) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                currency, exponent, 1);
    }
}
