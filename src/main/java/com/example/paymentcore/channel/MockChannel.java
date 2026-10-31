package com.example.paymentcore.channel;

import com.example.paymentcore.domain.ChannelScenario;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 本地模拟渠道。只用于验证超时、处理中、重复请求与乱序查询，不代表真实渠道契约。
 * 以渠道幂等键为主键保证重复请求返回原结果，不产生第二笔资金效果。
 */
@Component
public class MockChannel implements ChannelPort {

    private final JdbcTemplate jdbc;

    public MockChannel(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void setScenario(String merchantOrderId, ChannelScenario scenario) {
        jdbc.update("MERGE INTO mock_channel_scenario KEY(merchant_order_id) VALUES (?, ?)",
                merchantOrderId, scenario.name());
    }

    @Override
    public ChannelResult charge(String providerIdempotencyKey, String merchantOrderId, long amountMinor,
                                String currency) {
        ChannelResult replay = readExisting(providerIdempotencyKey);
        if (replay != null) {
            return replay;
        }
        ChannelScenario scenario = scenarioOf(merchantOrderId);
        String reference = "mock_" + UUID.randomUUID();
        try {
            switch (scenario) {
                case SUCCESS -> {
                    insert(providerIdempotencyKey, "SUCCEEDED", reference, amountMinor, currency);
                    return ChannelResult.success(reference, amountMinor, currency);
                }
                case CHARGE_THEN_TIMEOUT -> {
                    // 渠道已扣款，但响应在本地丢失：只能进入 UNKNOWN 并靠查单找回
                    insert(providerIdempotencyKey, "SUCCEEDED", reference, amountMinor, currency);
                    return ChannelResult.timeout("扣款已执行但响应超时");
                }
                case PROCESSING_THEN_SUCCESS -> {
                    insert(providerIdempotencyKey, "PROCESSING", reference, amountMinor, currency);
                    return ChannelResult.processing(reference, amountMinor, currency);
                }
                case DECLINE -> {
                    insert(providerIdempotencyKey, "FAILED", reference, amountMinor, currency);
                    return ChannelResult.failed("渠道拒绝");
                }
                default -> throw new IllegalStateException("未知场景: " + scenario);
            }
        } catch (DuplicateKeyException e) {
            // 并发重复请求：数据库唯一约束裁决后返回原结果
            ChannelResult existing = readExisting(providerIdempotencyKey);
            if (existing != null) {
                return existing;
            }
            throw e;
        }
    }

    @Override
    public ChannelResult query(String providerIdempotencyKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, provider_reference, amount_minor, currency FROM mock_channel_tx "
                        + "WHERE provider_idempotency_key = ?", providerIdempotencyKey);
        if (rows.isEmpty()) {
            return ChannelResult.notFound("渠道暂未查到该操作");
        }
        Map<String, Object> row = rows.get(0);
        String status = (String) row.get("status");
        String reference = (String) row.get("provider_reference");
        long amount = ((Number) row.get("amount_minor")).longValue();
        String currency = (String) row.get("currency");

        if ("PROCESSING".equals(status)) {
            // 模拟渠道后续完成：查询一次即视为已完成，产生可核对的成功证据
            jdbc.update("UPDATE mock_channel_tx SET status = 'SUCCEEDED' "
                    + "WHERE provider_idempotency_key = ? AND status = 'PROCESSING'", providerIdempotencyKey);
            return ChannelResult.success(reference, amount, currency);
        }
        if ("SUCCEEDED".equals(status)) {
            return ChannelResult.success(reference, amount, currency);
        }
        return ChannelResult.failed("渠道确认失败");
    }

    @Override
    public ChannelResult refund(String providerIdempotencyKey, String merchantOrderId, long amountMinor,
                                String currency) {
        ChannelResult replay = readExistingRefund(providerIdempotencyKey);
        if (replay != null) {
            return replay;
        }
        ChannelScenario scenario = scenarioOf(merchantOrderId);
        String reference = "mock_rf_" + UUID.randomUUID();
        try {
            switch (scenario) {
                case SUCCESS -> {
                    insertRefund(providerIdempotencyKey, "SUCCEEDED", reference, amountMinor, currency);
                    return ChannelResult.success(reference, amountMinor, currency);
                }
                case CHARGE_THEN_TIMEOUT -> {
                    insertRefund(providerIdempotencyKey, "SUCCEEDED", reference, amountMinor, currency);
                    return ChannelResult.timeout("退款已执行但响应超时");
                }
                case PROCESSING_THEN_SUCCESS -> {
                    insertRefund(providerIdempotencyKey, "PROCESSING", reference, amountMinor, currency);
                    return ChannelResult.processing(reference, amountMinor, currency);
                }
                case DECLINE -> {
                    insertRefund(providerIdempotencyKey, "FAILED", reference, amountMinor, currency);
                    return ChannelResult.failed("渠道拒绝退款");
                }
                default -> throw new IllegalStateException("未知场景: " + scenario);
            }
        } catch (DuplicateKeyException e) {
            ChannelResult existing = readExistingRefund(providerIdempotencyKey);
            if (existing != null) {
                return existing;
            }
            throw e;
        }
    }

    @Override
    public ChannelResult queryRefund(String providerIdempotencyKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, provider_reference, amount_minor, currency FROM mock_channel_refund_tx "
                        + "WHERE provider_idempotency_key = ?", providerIdempotencyKey);
        if (rows.isEmpty()) {
            return ChannelResult.notFound("渠道暂未查到该退款");
        }
        Map<String, Object> row = rows.get(0);
        String status = (String) row.get("status");
        String reference = (String) row.get("provider_reference");
        long amount = ((Number) row.get("amount_minor")).longValue();
        String currency = (String) row.get("currency");
        if ("PROCESSING".equals(status)) {
            jdbc.update("UPDATE mock_channel_refund_tx SET status = 'SUCCEEDED' "
                    + "WHERE provider_idempotency_key = ? AND status = 'PROCESSING'", providerIdempotencyKey);
            return ChannelResult.success(reference, amount, currency);
        }
        if ("SUCCEEDED".equals(status)) {
            return ChannelResult.success(reference, amount, currency);
        }
        return ChannelResult.failed("渠道确认退款失败");
    }

    private ChannelResult readExistingRefund(String providerIdempotencyKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, provider_reference, amount_minor, currency FROM mock_channel_refund_tx "
                        + "WHERE provider_idempotency_key = ?", providerIdempotencyKey);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        String status = (String) row.get("status");
        String reference = (String) row.get("provider_reference");
        long amount = ((Number) row.get("amount_minor")).longValue();
        String currency = (String) row.get("currency");
        return switch (status) {
            case "SUCCEEDED" -> ChannelResult.success(reference, amount, currency);
            case "PROCESSING" -> ChannelResult.processing(reference, amount, currency);
            default -> ChannelResult.failed("渠道退款记录已失败");
        };
    }

    private void insertRefund(String providerIdempotencyKey, String status, String reference, long amountMinor,
                              String currency) {
        jdbc.update("INSERT INTO mock_channel_refund_tx(provider_idempotency_key, status, provider_reference, "
                        + "amount_minor, currency) VALUES (?, ?, ?, ?, ?)",
                providerIdempotencyKey, status, reference, amountMinor, currency);
    }

    private ChannelResult readExisting(String providerIdempotencyKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, provider_reference, amount_minor, currency FROM mock_channel_tx "
                        + "WHERE provider_idempotency_key = ?", providerIdempotencyKey);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        String status = (String) row.get("status");
        String reference = (String) row.get("provider_reference");
        long amount = ((Number) row.get("amount_minor")).longValue();
        String currency = (String) row.get("currency");
        return switch (status) {
            case "SUCCEEDED" -> ChannelResult.success(reference, amount, currency);
            case "PROCESSING" -> ChannelResult.processing(reference, amount, currency);
            default -> ChannelResult.failed("渠道记录已失败");
        };
    }

    private ChannelScenario scenarioOf(String merchantOrderId) {
        List<String> rows = jdbc.queryForList(
                "SELECT scenario FROM mock_channel_scenario WHERE merchant_order_id = ?", String.class,
                merchantOrderId);
        if (rows.isEmpty()) {
            return ChannelScenario.SUCCESS;
        }
        return ChannelScenario.valueOf(rows.get(0).toUpperCase(Locale.ROOT));
    }

    private void insert(String providerIdempotencyKey, String status, String reference, long amountMinor,
                        String currency) {
        jdbc.update("INSERT INTO mock_channel_tx(provider_idempotency_key, status, provider_reference, "
                        + "amount_minor, currency) VALUES (?, ?, ?, ?, ?)",
                providerIdempotencyKey, status, reference, amountMinor, currency);
    }
}
