package com.example.paymentcore.outbox;

import com.example.paymentcore.domain.OutboxStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Outbox 发布器：先领取 PENDING，再以稳定 event_id 投递到模拟消息日志，最后标记 PUBLISHED。
 * 若在标记前崩溃，会重复投递；重复由 event_log 主键吸收，不生成新事件身份。
 */
@Service
public class OutboxPublisher {

    private static final int BATCH_LIMIT = 50;

    private final JdbcTemplate jdbc;

    public OutboxPublisher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int publishPending() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_id, event_type, payload FROM outbox_record WHERE status = ? "
                        + "ORDER BY created_at LIMIT " + BATCH_LIMIT,
                OutboxStatus.PENDING.name());

        int published = 0;
        for (Map<String, Object> row : rows) {
            String eventId = (String) row.get("event_id");
            int claimed = jdbc.update("UPDATE outbox_record SET publish_version = publish_version + 1 "
                            + "WHERE event_id = ? AND status = ?",
                    eventId, OutboxStatus.PENDING.name());
            if (claimed == 0) {
                continue; // 已被其他发布者领取
            }
            try {
                jdbc.update("INSERT INTO event_log(event_id, event_type, payload) VALUES (?, ?, ?)",
                        eventId, row.get("event_type"), row.get("payload"));
            } catch (DuplicateKeyException e) {
                // 重复投递：事件身份相同，接收侧去重，不产生新的业务效果
            }
            jdbc.update("UPDATE outbox_record SET status = ?, published_at = CURRENT_TIMESTAMP "
                            + "WHERE event_id = ?",
                    OutboxStatus.PUBLISHED.name(), eventId);
            published++;
        }
        return published;
    }
}
