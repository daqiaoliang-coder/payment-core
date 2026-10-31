package com.example.paymentcore.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 资金控制状态与来源水位的读取入口；资金批准必须读这里，而不是投影。 */
@Service
public class LedgerQuery {

    public record BalanceState(String accountId, long postedBalance, long reservedTotal, int version) {
    }

    public record JournalSummary(String journalId, String bookId, String kind, String journalStatus) {
    }

    private final JdbcTemplate jdbc;

    public LedgerQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BalanceState balance(String accountId) {
        var rows = jdbc.query(
                "SELECT account_id, posted_balance, reserved_total, version FROM account_balance "
                        + "WHERE account_id = ?",
                (rs, i) -> new BalanceState(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getInt(4)),
                accountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long sourceSequence() {
        Long sequence = jdbc.queryForObject(
                "SELECT COALESCE(MAX(entry_id), 0) FROM ledger_entry", Long.class);
        return sequence == null ? 0L : sequence;
    }

    public int journalCount(String operationKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_journal WHERE operation_key = ?", Integer.class, operationKey);
        return count == null ? 0 : count;
    }

    public long entryCount(String journalId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entry WHERE journal_id = ?", Long.class, journalId);
        return count == null ? 0L : count;
    }
}
