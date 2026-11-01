package com.example.paymentcore.security;

import com.example.paymentcore.common.ApiConflictException;
import com.example.paymentcore.common.ApiNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 地域权威：切换后旧的地区写入者不得继续本地写入。
 * 迟到的外部效果只能进入核查队列，不能直接改变当前权威状态。
 */
@Service
public class AuthorityService {

    public static final String STALE_WRITER = "stale_region_writer";
    public static final String STATUS_INSPECTION = "INSPECTION";

    private static final int DETAIL_LIMIT = 500;

    public record ScopeState(String scopeId, String activeRegion, int epoch, Instant updatedAt) {
    }

    public record LateEffectView(String lateEffectId, String scopeId, String region, int epoch,
                                 String externalRef, String detail, String status, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final SensitiveDataSanitizer sanitizer;
    private final SecurityAuditService audit;

    public AuthorityService(JdbcTemplate jdbc, SensitiveDataSanitizer sanitizer, SecurityAuditService audit) {
        this.jdbc = jdbc;
        this.sanitizer = sanitizer;
        this.audit = audit;
    }

    public ScopeState switchTo(String scopeId, String region) {
        int updated = jdbc.update("UPDATE authority_scope SET active_region = ?, epoch = epoch + 1, "
                + "updated_at = CURRENT_TIMESTAMP WHERE scope_id = ?", region, scopeId);
        if (updated == 0) {
            try {
                jdbc.update("INSERT INTO authority_scope(scope_id, active_region, epoch) VALUES (?, ?, 1)",
                        scopeId, region);
            } catch (DuplicateKeyException e) {
                // 并发初始化：由主键裁决，随后递增 epoch
                jdbc.update("UPDATE authority_scope SET active_region = ?, epoch = epoch + 1, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE scope_id = ?", region, scopeId);
            }
        }
        return current(scopeId);
    }

    public ScopeState current(String scopeId) {
        List<ScopeState> rows = jdbc.query("SELECT scope_id, active_region, epoch, updated_at "
                        + "FROM authority_scope WHERE scope_id = ?",
                (rs, i) -> new ScopeState(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getTimestamp(4).toInstant()),
                scopeId);
        if (rows.isEmpty()) {
            throw new ApiNotFoundException("权威范围不存在: " + scopeId);
        }
        return rows.get(0);
    }

    /** 本地写入前置校验：地域或 epoch 任一失效即拒绝，不能让旧写入者继续落库。 */
    public ScopeState requireActive(String scopeId, String region, int epoch) {
        List<ScopeState> rows = jdbc.query("SELECT scope_id, active_region, epoch, updated_at "
                        + "FROM authority_scope WHERE scope_id = ?",
                (rs, i) -> new ScopeState(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getTimestamp(4).toInstant()),
                scopeId);
        if (rows.isEmpty()) {
            throw new ApiConflictException(STALE_WRITER, "权威范围尚未初始化，拒绝写入");
        }
        ScopeState state = rows.get(0);
        if (!state.activeRegion().equals(region) || state.epoch() != epoch) {
            audit.record("authority-writer", "AUTHORITY_WRITE", scopeId,
                    SecurityAuditService.OUTCOME_REJECTED,
                    "stale writer region=" + region + " epoch=" + epoch);
            throw new ApiConflictException(STALE_WRITER,
                    "旧地域写入者已失效: region=" + region + ", epoch=" + epoch
                            + ", active=" + state.activeRegion() + "/" + state.epoch());
        }
        return state;
    }

    /** 迟到效果只登记核查，不应用其业务变更。 */
    public LateEffectView recordLateEffect(String scopeId, String region, int epoch, String externalRef,
                                           String detail, String actor) {
        String existingId = findLateEffectId(scopeId, externalRef);
        if (existingId != null) {
            return lateEffect(existingId);
        }
        String lateEffectId = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO late_effect(late_effect_id, scope_id, region, epoch, external_ref, detail, "
                            + "status) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    lateEffectId, scopeId, region, epoch, externalRef, truncate(sanitizer.sanitize(detail)),
                    STATUS_INSPECTION);
        } catch (DuplicateKeyException e) {
            return lateEffect(findLateEffectId(scopeId, externalRef));
        }
        audit.record(actor, "LATE_EFFECT", scopeId, SecurityAuditService.OUTCOME_RECORDED,
                "externalRef=" + externalRef + " region=" + region + " epoch=" + epoch + " -> " + STATUS_INSPECTION);
        return lateEffect(lateEffectId);
    }

    public List<LateEffectView> lateEffects(String scopeId) {
        return jdbc.query("SELECT late_effect_id, scope_id, region, epoch, external_ref, detail, status, "
                        + "created_at FROM late_effect WHERE scope_id = ? ORDER BY created_at, late_effect_id",
                (rs, i) -> new LateEffectView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant()),
                scopeId);
    }

    private String findLateEffectId(String scopeId, String externalRef) {
        List<String> ids = jdbc.queryForList("SELECT late_effect_id FROM late_effect "
                + "WHERE scope_id = ? AND external_ref = ?", String.class, scopeId, externalRef);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private LateEffectView lateEffect(String lateEffectId) {
        List<LateEffectView> rows = jdbc.query("SELECT late_effect_id, scope_id, region, epoch, external_ref, "
                        + "detail, status, created_at FROM late_effect WHERE late_effect_id = ?",
                (rs, i) -> new LateEffectView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant()),
                lateEffectId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("迟到效果记录写入后无法读取: " + lateEffectId);
        }
        return rows.get(0);
    }

    private String truncate(String value) {
        if (value == null || value.length() <= DETAIL_LIMIT) {
            return value;
        }
        return value.substring(0, DETAIL_LIMIT);
    }
}
