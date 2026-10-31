package com.example.paymentcore.settlement;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 启动时准备默认费用规则版本；真实实现必须由合同与配置驱动。 */
@Component
public class SettlementBootstrap implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public SettlementBootstrap(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.update("MERGE INTO fee_rule KEY(rule_version) VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP)",
                "fee-v1", 100, 0L);
    }
}
