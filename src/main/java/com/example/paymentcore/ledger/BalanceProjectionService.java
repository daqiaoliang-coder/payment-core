package com.example.paymentcore.ledger;

import com.example.paymentcore.common.ApiConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 余额投影：从源账本分录与有效预留重放得到读模型。
 * 必须暴露消费水位；资金批准不得使用落后投影（L08/L9）。
 */
@Service
public class BalanceProjectionService {

    public record Projection(String accountId, long postedBalance, long activeReserved, long sequence,
                             Instant asOf) {
    }

    private final JdbcTemplate jdbc;
    private final LedgerQuery query;

    public BalanceProjectionService(JdbcTemplate jdbc, LedgerQuery query) {
        this.jdbc = jdbc;
        this.query = query;
    }

    @Transactional
    public void rebuild() {
        long sequence = query.sourceSequence();
        Timestamp asOf = Timestamp.from(Instant.now());
        List<String> accountIds = jdbc.queryForList("SELECT account_id FROM ledger_account ORDER BY account_id",
                String.class);
        jdbc.update("DELETE FROM balance_projection");
        for (String accountId : accountIds) {
            Long posted = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(CASE WHEN e.side = a.normal_side THEN e.amount_minor "
                            + "ELSE -e.amount_minor END), 0) FROM ledger_entry e "
                            + "JOIN ledger_account a ON a.account_id = e.account_id WHERE e.account_id = ?",
                    Long.class, accountId);
            Long reserved = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount_minor), 0) FROM refund_reservation "
                            + "WHERE account_id = ? AND status = 'RESERVED'", Long.class, accountId);
            // 阶段 3：未终结出款同样占用商户应付权利，必须计入有效预留
            Long payoutReserved = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(p.amount_minor), 0) FROM payout_order p "
                            + "JOIN ledger_account a ON a.merchant_id = p.merchant_id "
                            + "AND a.currency = p.currency "
                            + "WHERE a.account_id = ? AND a.category = ? "
                            + "AND p.status IN ('CREATED', 'PROCESSING', 'UNKNOWN')",
                    Long.class, accountId, LedgerConstants.MERCHANT_PAYABLE);
            long activeReserved = (reserved == null ? 0L : reserved)
                    + (payoutReserved == null ? 0L : payoutReserved);
            jdbc.update("INSERT INTO balance_projection(account_id, posted_balance, active_reserved, sequence, "
                            + "as_of) VALUES (?, ?, ?, ?, ?)",
                    accountId, posted == null ? 0L : posted, activeReserved, sequence, asOf);
        }
    }

    public Projection snapshot(String accountId) {
        List<Projection> rows = jdbc.query(
                "SELECT account_id, posted_balance, active_reserved, sequence, as_of FROM balance_projection "
                        + "WHERE account_id = ?",
                (rs, i) -> new Projection(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getTimestamp(5).toInstant()),
                accountId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 投影水位落后于源账本时抛错，防止用旧余额批准资金动作。 */
    public void assertFresh(String accountId) {
        Projection projection = snapshot(accountId);
        if (projection == null) {
            throw new ApiConflictException("missing_projection", "账户尚无投影: " + accountId);
        }
        if (projection.sequence() < query.sourceSequence()) {
            throw new ApiConflictException("stale_projection",
                    "投影水位 " + projection.sequence() + " 落后于源账本 " + query.sourceSequence());
        }
    }
}
