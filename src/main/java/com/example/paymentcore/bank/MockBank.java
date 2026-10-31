package com.example.paymentcore.bank;

import com.example.paymentcore.domain.BankScenario;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 本地模拟银行。银行与本地账本没有共同事务：发起前先持久化出款意图，
 * 超时只代表本地未取得结果，查单复用原银行幂等键，不产生第二笔资金效果。
 */
@Component
public class MockBank {

    public enum BankOutcome {
        SUCCESS,
        FAILED,
        TIMEOUT,
        PROCESSING,
        NOT_FOUND
    }

    public record BankResult(BankOutcome outcome, String bankReference, String detail) {

        public static BankResult success(String bankReference) {
            return new BankResult(BankOutcome.SUCCESS, bankReference, "银行已扣账");
        }

        public static BankResult processing(String bankReference) {
            return new BankResult(BankOutcome.PROCESSING, bankReference, "银行处理中");
        }

        public static BankResult failed(String detail) {
            return new BankResult(BankOutcome.FAILED, null, detail);
        }

        public static BankResult timeout(String bankReference, String detail) {
            return new BankResult(BankOutcome.TIMEOUT, bankReference, detail);
        }

        public static BankResult notFound(String detail) {
            return new BankResult(BankOutcome.NOT_FOUND, null, detail);
        }
    }

    private final JdbcTemplate jdbc;

    public MockBank(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void setScenario(String merchantId, BankScenario scenario) {
        jdbc.update("MERGE INTO mock_bank_scenario KEY(merchant_id) VALUES (?, ?)", merchantId, scenario.name());
    }

    public BankResult pay(String providerIdempotencyKey, String merchantId, long amountMinor, String currency) {
        BankResult replay = readExisting(providerIdempotencyKey);
        if (replay != null) {
            return replay;
        }
        BankScenario scenario = scenarioOf(merchantId);
        String reference = "bank_" + UUID.randomUUID();
        try {
            switch (scenario) {
                case SUCCESS -> {
                    insert(providerIdempotencyKey, "SUCCEEDED", reference, amountMinor, currency);
                    return BankResult.success(reference);
                }
                case TIMEOUT_AFTER_DEBIT -> {
                    // 银行已扣账但响应丢失：只能进入 UNKNOWN，并靠原身份查单收敛
                    insert(providerIdempotencyKey, "SUCCEEDED", reference, amountMinor, currency);
                    return BankResult.timeout(reference, "银行已扣账但响应超时");
                }
                case DECLINE -> {
                    insert(providerIdempotencyKey, "DECLINED", reference, amountMinor, currency);
                    return BankResult.failed("银行明确拒绝");
                }
                case PROCESSING_THEN_SUCCESS -> {
                    insert(providerIdempotencyKey, "PROCESSING", reference, amountMinor, currency);
                    return BankResult.processing(reference);
                }
                default -> throw new IllegalStateException("未知银行场景: " + scenario);
            }
        } catch (DuplicateKeyException e) {
            BankResult existing = readExisting(providerIdempotencyKey);
            if (existing != null) {
                return existing;
            }
            throw e;
        }
    }

    public BankResult query(String providerIdempotencyKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, bank_reference FROM mock_bank_tx WHERE provider_idempotency_key = ?",
                providerIdempotencyKey);
        if (rows.isEmpty()) {
            return BankResult.notFound("银行暂未查到该付款指令");
        }
        Map<String, Object> row = rows.get(0);
        String status = (String) row.get("status");
        String reference = (String) row.get("bank_reference");
        if ("PROCESSING".equals(status)) {
            jdbc.update("UPDATE mock_bank_tx SET status = 'SUCCEEDED' "
                    + "WHERE provider_idempotency_key = ? AND status = 'PROCESSING'", providerIdempotencyKey);
            return BankResult.success(reference);
        }
        if ("SUCCEEDED".equals(status)) {
            return BankResult.success(reference);
        }
        return BankResult.failed("银行确认该付款指令已拒绝");
    }

    private BankResult readExisting(String providerIdempotencyKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, bank_reference FROM mock_bank_tx WHERE provider_idempotency_key = ?",
                providerIdempotencyKey);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        String status = (String) row.get("status");
        String reference = (String) row.get("bank_reference");
        return switch (status) {
            case "SUCCEEDED" -> BankResult.success(reference);
            case "PROCESSING" -> BankResult.processing(reference);
            default -> BankResult.failed("银行记录已拒绝");
        };
    }

    private BankScenario scenarioOf(String merchantId) {
        List<String> rows = jdbc.queryForList(
                "SELECT scenario FROM mock_bank_scenario WHERE merchant_id = ?", String.class, merchantId);
        if (rows.isEmpty()) {
            return BankScenario.SUCCESS;
        }
        return BankScenario.valueOf(rows.get(0).toUpperCase(Locale.ROOT));
    }

    private void insert(String providerIdempotencyKey, String status, String reference, long amountMinor,
                        String currency) {
        jdbc.update("INSERT INTO mock_bank_tx(provider_idempotency_key, status, bank_reference, "
                        + "amount_minor, currency) VALUES (?, ?, ?, ?, ?)",
                providerIdempotencyKey, status, reference, amountMinor, currency);
    }
}
