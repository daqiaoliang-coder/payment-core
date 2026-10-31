package com.example.paymentcore.settlement;

import com.example.paymentcore.common.ApiConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** 版本化费用规则：清分明细必须保存所用规则版本，不能按当前配置倒推历史。 */
@Service
public class FeeRuleService {

    public record FeeRule(String ruleVersion, int feeBps, long fixedFeeMinor) {

        public long feeFor(long grossMinor) {
            return grossMinor * feeBps / 10_000 + fixedFeeMinor;
        }
    }

    private final JdbcTemplate jdbc;

    public FeeRuleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public FeeRule activeRule() {
        List<FeeRule> rows = jdbc.query(
                "SELECT rule_version, fee_bps, fixed_fee_minor FROM fee_rule WHERE active = TRUE "
                        + "ORDER BY effective_at DESC, rule_version DESC LIMIT 1",
                (rs, i) -> new FeeRule(rs.getString(1), rs.getInt(2), rs.getLong(3)));
        if (rows.isEmpty()) {
            throw new ApiConflictException("missing_fee_rule", "没有生效的费用规则");
        }
        return rows.get(0);
    }

    public FeeRule requireRule(String ruleVersion) {
        if (ruleVersion == null || ruleVersion.isBlank()) {
            return activeRule();
        }
        List<FeeRule> rows = jdbc.query(
                "SELECT rule_version, fee_bps, fixed_fee_minor FROM fee_rule WHERE rule_version = ?",
                (rs, i) -> new FeeRule(rs.getString(1), rs.getInt(2), rs.getLong(3)), ruleVersion);
        if (rows.isEmpty()) {
            throw new ApiConflictException("unknown_fee_rule", "未知费用规则: " + ruleVersion);
        }
        return rows.get(0);
    }
}
