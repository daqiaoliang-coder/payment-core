# 参考资料与证据边界

> 资料核对日期：2026-10-30。以下链接用于解释公开产品语义与成熟工程模式，不代表本项目已集成这些产品，也不构成对所有支付渠道的通用保证。

## 1. 证据分级

| 类别 | 含义 | 使用限制 |
| --- | --- | --- |
| 产品事实 | 官方文档描述的产品行为、作用域或限制 | 只适用于对应产品、接口、账户和支付方式 |
| 工程模式 | 官方或成熟技术材料描述的模式与边界 | 需要结合具体数据库、消息系统和业务效果验证 |
| 设计推论 | 本项目基于上述材料提出的模块、约束与流程 | 未实现、未测试，不能当成运行事实 |
| 待核实事项 | 合同、监管、渠道能力、会计政策或运行指标 | 落地前必须取得实际证据 |

本仓库的架构图、状态表、金额示例和验证矩阵属于设计推论。引用官方文档不自动证明这些设计已经正确实现。

## 2. 交易、通知与幂等

[1] [Stripe：Payment Intents](https://docs.stripe.com/payments/payment-intents)

- 支付意图可关联多个 Charge，体现收款目标与执行记录的区分。
- `requires_capture` 与 `succeeded` 等状态有产品特定含义。
- 不能据此声称跨服务商仍只会成功扣款一次。

[2] [Adyen：Payments lifecycle](https://docs.adyen.com/account/payments-lifecycle)

- 授权、捕获、退款、拒付和结算处于不同阶段。
- `Settled` 表示 Adyen 收到资金，不代表已经向商户付款。
- 不同支付方式的生命周期和最终性需要分别映射。

[3] [Stripe：Idempotent requests](https://docs.stripe.com/api/idempotent_requests)

- 幂等键在至少 24 小时后可能被清理，不是永久业务防重。
- 已开始执行的请求可能保存并返回首次结果，包括 500 响应。
- 参数校验失败、并发冲突等未开始执行的情形需区别处理。

[4] [Adyen：API idempotency](https://docs.adyen.com/development-resources/api-idempotency)

- 当前核对文本给出 company account 作用域及 7—14 天保留窗口。
- 不同区域端点之间不检查重复请求。
- 不能把该窗口套用到 Stripe、其他渠道或本地永久去重记录。

[5] [Stripe：Webhooks](https://docs.stripe.com/webhooks)

- 事件可能重复投递，且不保证按生成顺序送达。
- 验签需要按产品规范处理原始请求内容。
- 传输事件 ID 去重不替代业务事实与账务效果去重。

[9] [Adyen：Refund](https://docs.adyen.com/online-payments/refund)

- `REFUND` 中 `success=true` 表示退款请求通过校验，之后仍可能失败或退回。
- 不能把该字段直接解释为客户最终收到退款。

## 3. 本地一致性与消息边界

[6] [AWS：Transactional outbox pattern](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)

- 业务更新与 outbox 在同一数据库事务中提交，避免本地双写断裂。
- 转发仍可能重复，消费者需要幂等。
- 不证明数据库、消息系统和外部渠道具有全局原子提交。

[7] [Apache Kafka：Design / Delivery Semantics](https://kafka.apache.org/41/design/design/)

- Kafka 的投递与事务语义有明确作用范围。
- 对外部目标实现恰好一次通常需要目标系统配合。
- 不能据此推导银行扣款、退款或出款恰好一次。

## 4. 账务、结算与对账

[8] [TigerBeetle：Two-phase transfers](https://docs.tigerbeetle.com/coding/two-phase-transfers/)

- pending transfer 可被 post 或 void 终结一次，完成通过新增 transfer 表达。
- 该语义用于理解预留与不可变记录，不等同完整支付退款协议。
- 不能泛化为同一预留可反复部分捕获，也不能把本地预留过期当作外部 UNKNOWN 失败证据。

[10] [Adyen：Settlement details report](https://docs.adyen.com/reporting/settlement-reconciliation/transaction-level/settlement-details-report)

- 提供交易级结算明细、费用、退款和调整等核对依据。
- 字段和口径只适用于对应报告产品。

[11] [Adyen：Transaction-level reconciliation](https://docs.adyen.com/reporting/settlement-reconciliation/transaction-level)

- 展示交易净额与批次出款之间的核对关系。
- 跨批次、周末或迟到交易可能进入后续批次。
- 本项目的四层对账框架是设计推论，不是该报告的完整复制。

## 5. 跨地域一致性

[12] [Google Cloud：TrueTime and external consistency](https://docs.cloud.google.com/spanner/docs/true-time-external-consistency)

- Spanner 事务可跨服务器、跨数据中心保持外部一致性。
- 证明多地域强一致并非不可能，但不意味着无需协调或没有延迟成本。
- 不能据此为任意数据库、异步复制或双写多活背书。

## 6. 尚未取得证据的事项

- 本项目的运行实现、自动化测试、容量与故障演练结果。
- 具体地区的支付许可、客户资金隔离、税务和会计政策。
- 真实渠道的幂等保留、查单可见性、撤销最终性和退款到账语义。
- 具体数据库隔离级别、跨分片协议和灾备恢复能力。
- 未经测量的吞吐量、延迟、RPO、RTO 与服务等级目标。

## 7. 维护规则

- 产品行为变化时，重新核对官方文档并更新受影响的状态映射与验证用例。
- 不把旧版本窗口、区域作用域或产品字段静默推广到新接口。
- 新增设计结论时，标明它是产品事实、工程模式、设计推论还是待核实事项。
- 修改金额示例、状态机或不变量时，同步检查[验证矩阵](validation-matrix.md)。

[返回首页](../README.md) · [架构决策](architecture.md)
