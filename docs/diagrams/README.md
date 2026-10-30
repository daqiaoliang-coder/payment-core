# 架构图集

> 所有图均为参考设计，未实现、未部署。Mermaid 用于 GitHub 原生展示；`.mmd` 文件是可维护图源，Markdown 中的同名图块用于首页阅读。

## 阅读顺序

1. 核心架构：先看交易、账务、资金与核对闭环的边界。
2. 渠道尝试状态：理解 UNKNOWN、终态证据和迟到冲突。
3. 超时恢复时序：理解本地事务、外部扣款与幂等记账的交界。

图不替代正文中的合同前提、渠道契约和[验证矩阵](../validation-matrix.md)。

## 1. 核心架构

实线表示请求、指令或计算结果；虚线表示异步事件、通知或对账资料。风险控制是跨操作约束，图中仅简化展示入口。

```mermaid
flowchart TB
    merchant["商户业务系统"]
    subgraph platform["支付平台：逻辑边界（参考设计，未实现）"]
        access["接入、认证与风险控制"]
        payment["支付与退款编排"]
        channel["渠道适配与安全路由"]
        ledger["账本、余额与资金预留"]
        clearing["清分、计费与应结计算"]
        settlement["结算批次与出款"]
        recon["对账与差错处理"]
    end
    provider["外部支付渠道"]
    bank["银行或托管机构"]
    merchant -->|业务请求| access
    access -->|授权后的操作| payment
    payment -->|持久化的资金操作| channel
    channel -->|扣款、查单、撤销、退款| provider
    provider -.->|可信结果证据| channel
    channel -->|标准化结果| payment
    payment -.->|Outbox：资金业务事件| ledger
    ledger -.->|已入账事实| clearing
    clearing -->|应结结果| settlement
    settlement -->|出款指令，经机构适配| bank
    settlement -.->|出款资金事件| ledger
    provider -.->|交易与结算明细| recon
    bank -.->|实际资金流水| recon
    payment -.->|内部交易记录| recon
    ledger -.->|内部账项| recon
```

图源：[core-architecture.mmd](core-architecture.mmd)。说明：[领域边界与架构决策](../architecture.md)。

## 2. 渠道尝试状态

本图限定收款尝试，不直接套用为退款或拒付状态机。`UNKNOWN` 是本地知识状态；终态后的相反资金证据必须保存并进入差错，而不是被丢弃。

```mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> SUBMITTED: 取得执行权并持久化发送意图
    CREATED --> CANCELLED: 封闭发送权限且没有外部效果
    CREATED --> FAILED: 本地校验拒绝且未发送
    SUBMITTED --> PROCESSING: 渠道明确处理中
    SUBMITTED --> UNKNOWN: 响应缺失或含义不确定
    SUBMITTED --> SUCCEEDED: 取得约定资金操作成功证据
    SUBMITTED --> FAILED: 明确失败且无后续扣款
    SUBMITTED --> CANCELLED: 确认取消且不能再扣款
    PROCESSING --> UNKNOWN: 后续结果无法确认
    PROCESSING --> SUCCEEDED: 可信通知或查单确认
    PROCESSING --> FAILED: 明确失败且无后续扣款
    PROCESSING --> CANCELLED: 确认取消且不能再扣款
    UNKNOWN --> UNKNOWN: 继续查单或升级处理
    UNKNOWN --> PROCESSING: 确认仍在处理
    UNKNOWN --> SUCCEEDED: 取得成功证据
    UNKNOWN --> FAILED: 确认失败且无后续扣款
    UNKNOWN --> CANCELLED: 确认取消且不能再扣款
    SUCCEEDED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
    note right of UNKNOWN
        本地结果未知，不等于渠道失败。
        不能仅因超时切换渠道重新扣款。
    end note
    note right of SUCCEEDED
        退款、拒付属于独立后续操作。
        与终态冲突的迟到证据进入差错流程。
    end note
```

图源：[payment-attempt.mmd](payment-attempt.mmd)。说明：[支付生命周期](../payment-lifecycle.md)。

## 3. 超时恢复时序

本图展示“渠道可能已经扣款、本地响应丢失”的恢复路径，到幂等入账为止；资金到账仍需后续结算与银行证据。

```mermaid
sequenceDiagram
    autonumber
    participant B as 商户业务
    participant P as 支付编排
    participant D as 支付数据库
    participant W as 持久化任务执行器
    participant C as 外部渠道
    participant L as 账务服务
    B->>P: 创建支付（业务幂等身份）
    P->>D: 事务 A：支付单、尝试、发送任务
    D-->>P: 提交成功
    P-->>B: 已受理，不代表已扣款
    W->>D: 原子领取任务并保存 SUBMITTED
    W->>C: 扣款（固定渠道请求身份）
    C--xW: 响应丢失，渠道可能已经扣款
    W->>D: 保存 UNKNOWN 与查单任务
    Note over D,C: 本地数据库事务不包含外部扣款
    W->>C: 按原请求身份查单
    C-->>W: 成功证据
    W->>D: 事务 B：结果证据、支付状态、记账 Outbox
    W->>L: 至少一次投递资金业务事件
    L->>L: 事务 C：唯一凭证、完整分录、余额约束
    L-->>W: 新入账或返回已有结果
    Note over W,L: 重复投递不得增加第二份业务分录
    Note over B,L: 本图到入账为止，资金到账需后续结算证据
```

图源：[timeout-recovery.mmd](timeout-recovery.mmd)。说明：[一致性与恢复](../consistency-and-recovery.md)。

## 维护约定

- 修改 `.mmd` 后同步对应 Markdown 图块，避免首页与图源分叉。
- 新增状态、事件或资金门禁时，同步检查状态表、事务边界和验证矩阵。
- 图中不出现真实账户、密钥、商户数据或未经证实的运行指标。
- 本目录不声称图已经通过真实系统验证；当前只进行结构与内容一致性检查。

[返回首页](../../README.md) · [参考资料](../references.md)
