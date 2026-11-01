package com.example.paymentcore.monitoring;

import com.example.paymentcore.security.OperationAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 只读监控入口：运行快照、健康信号与操作审计查询。 */
@RestController
@RequestMapping("/api/monitoring")
public class MonitoringController {

    private final MonitoringService monitoring;
    private final OperationAuditService audit;

    public MonitoringController(MonitoringService monitoring, OperationAuditService audit) {
        this.monitoring = monitoring;
        this.audit = audit;
    }

    @GetMapping("/snapshot")
    public MonitoringService.Snapshot snapshot() {
        return monitoring.snapshot();
    }

    @GetMapping("/health")
    public MonitoringService.HealthView health() {
        return monitoring.health();
    }

    @GetMapping("/audits")
    public List<OperationAuditService.AuditView> audits(@RequestParam(required = false) String businessId,
                                                        @RequestParam(required = false) String traceId,
                                                        @RequestParam(required = false) String action,
                                                        @RequestParam(defaultValue = "50") int limit) {
        if (businessId != null && !businessId.isBlank()) {
            return audit.findByBusinessId(businessId);
        }
        if (traceId != null && !traceId.isBlank()) {
            return audit.findByTraceId(traceId);
        }
        if (action != null && !action.isBlank()) {
            return audit.findByAction(action);
        }
        return audit.recent(limit);
    }
}
