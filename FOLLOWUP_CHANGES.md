# 后续可靠性改进

## 已实现的行为

### 刷新会话

每次签发 JWT 增加随机 jti，避免同一秒签发相同 Token。登录在 Redis 保存刷新令牌哈希、会话族和 access 到会话族的映射。刷新通过 Lua 原子比较旧哈希并替换为新哈希；同一旧令牌第二次使用会撤销该刷新会话族。退出可根据 Access Token 定位并删除刷新会话。Redis 故障不放行刷新。客户端必须将刷新请求串行化，并原子替换保存的一对 Token；刷新响应丢失需要重新登录，重复刷新采用严格撤销策略。

这是刷新凭证撤销，不是立即撤销该会话已经签发的全部 Access Token。退出接口保留原有“当前提交 Token 黑名单”行为；其他 Access Token 仍可能存活到过期。Redis 需要配置持久化与安全访问；键丢失会要求重新登录。旧版未登记会话的 Refresh Token 不再可刷新。

### 审核正文绑定

tb_document_review.content_revision 保存提交时的 MongoDB 正文 ID。审批时锁定文档并比较该 ID，正文版本不同则拒绝审批。编辑、自动保存、正文修改、恢复版本与提交审核使用同一文档行锁；待审核期间拒绝修改正文。查询接口不再自动补建缺少版本绑定的审核任务。历史未绑定任务不能直接批准，需要退回草稿再提交。

### 审核通知

审核提交、通过和驳回事件先写 MySQL Outbox，与审核结果同事务提交。后台按租约领取，等待 RabbitMQ correlated confirm，收到 nack、return、超时或异常时保留记录，一分钟后重试。生产者可能重复发送。foundation 用 eventId 的 Inbox 唯一主键去重，Inbox 与所有接收者的通知在同一个本地事务中落库；持久化异常继续抛给监听容器。WebSocket 只在数据库提交后尽力推送，离线以通知列表为准。

当前失败事件持续重试，没有自动丢弃或自动删除；应监控 Outbox attempts、积压、消费者异常，人工修复不可处理的数据。原 Rabbit 默认自动确认在监听方法正常返回后进行，事务失败必须抛出。消息持久化、持久队列与 exchange 继续沿用原配置。索引触发移到审批事务提交后，但索引任务本身未纳入此通知 Outbox，仍不具备持久重试。

### MySQL / MongoDB 正文一致性

保存正文新增 MongoDB 记录，不再覆盖旧正文；MySQL 元数据与有效正文指针一起提交。读取按 MySQL contentId 定位，不按 documentId 猜测最新 Mongo 记录。MySQL 事务回滚时旧指针继续有效，并尽力删除本次新正文；进程崩溃可能留下未引用记录，需要后续运维核对，但这些记录不会自动成为有效正文。本方案保证提交指针选择正文，不声称分布式原子事务。旧正文暂保留，不能仅按 documentId 批量清理，以免删除历史审核内容。

## 部署顺序

1. 停止旧版写入并备份 MySQL、MongoDB，先完成第一批版本号迁移。
2. 在 document 数据库执行 sql/migrations/20261113_reliable_review.sql 的 ALTER 和 Outbox 建表；在 foundation 数据库执行 Inbox 建表。若两个服务使用同一库则各执行一次。重复执行 ALTER 前检查列是否已存在。
3. 核对历史文档 contentId 都能定位 Mongo 正文；缺少指针的历史数据须人工修复。检查 document_content 上 documentId 索引不能是唯一索引，新设计允许一个 documentId 对应多条不可变正文记录。
4. 人工核对未完成且 content_revision 为空的审核任务：撤销旧任务、将文档退回草稿，再由作者重新提交。不要把当前正文 ID 自动填入历史审核记录。
5. 在停服窗口部署 document、foundation、user-auth，旧消息先处理完。新版消费者拒绝没有 eventId 的旧消息，不能混跑新旧生产者。document 与 foundation 的 InstanceIdentifier 必须稳定且一致，重启不能变，否则通知可能留在旧队列。Redis 旧会话用户重新登录。
6. MongoDB 使用适当的持久写确认（生产环境建议 majority）并做好备份；Redis 开启适当持久化。确保 MySQL 表均为 InnoDB。

## 验证与限制

单元测试使用 Mock 验证令牌重复签发隔离、未知刷新会话拒绝、轮换失败拒绝、退出定位会话、审核版本不匹配、待审核禁改、有效正文指针读取、Outbox ack/nack、消费者去重与异常传播。Mock 测试不证明实际 Redis Lua、InnoDB 锁或 Rabbit 故障恢复已通过。

部署前需要在隔离环境演练：并发刷新同一 Token；刷新后用旧 Access 退出再刷新；并发编辑与提交审核；Mongo 写成功后强制 MySQL 回滚；审核事务回滚时 Outbox 不留记录；MQ 断开后恢复；消息发送确认后进程退出造成重投；同一事件并发消费只生成一组通知；通知写入失败后恢复数据库再投递。当前未连接原远程环境执行这些演练，也未运行数据库迁移。

最终全模块验证：11 个模块构建成功，26 项测试通过（本批新增 13 项）。包含事务完成回调下的新正文回滚清理测试；真实数据库/Redis/RabbitMQ 集成演练仍待执行。
