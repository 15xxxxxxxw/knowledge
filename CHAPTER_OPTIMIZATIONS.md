# 智能知识库前 20 章源码优化台账

依据《传统后端面试学习》的章节顺序，核对原后端源码及两轮历史修改。本表的“已有”指此前两轮代码改动，“本轮”指此次新改动。“候选”只表示源码支持这种修改方向，实施前仍需要满足列出的条件。HTML 演示未作为功能验证依据。

|章|源码入口|已落地的变化或本章结论|仍可优化的方向及实施条件|
|---|---|---|---|
|1 项目地图|根 pom.xml、README|已有：整理可公开的后端副本，用环境变量替换部署凭证，保留迁移和部署说明。|完整启动验证需要独立的基础表结构和中间件配置，Maven 构建只能验证模块和测试。|
|2 请求链|InternalHeaderStripFilter.filter、AuthGlobalFilter.filter|本轮：外部请求的 INNER-REQUEST、X-User-Id 都在认证之前移除。普通业务路径仍由网关从已验签的访问令牌注入用户 ID。|部署时检查业务服务端口可达范围。经过网关的测试不能证明直连服务也受到保护。|
|3 认证授权|JwtAuthenticationFilter.parseToken（user-auth）、JwtTokenUtil、RefreshSessionService.rotate|本轮：删除用户认证过滤器的开发凭证分支，只从有效访问 JWT 加载用户。已有：区分 access/refresh，WebSocket 校验用途；随机 jti、Redis 刷新会话、原子轮换、重放撤销和退出定位会话。|若要求退出即断开所有设备或已有连接，需定义会话级失效范围并接入各认证入口。严格轮换下客户端需串行刷新。|
|4 对象与检索权限|DocumentAclService.getInaccessible、两处 DocumentAclProviderImpl、DocumentServiceImpl.assertDocumentVisible|本轮：ACL 开启时缺权限源、回源返回 null 或非法 ID 不再当作空限制。Feign 同时校验响应状态和列表；搜索、RAG、KAG 遇到 unresolved 均有拒绝分支。|详情、列表和索引可见范围仍需按团队规则统一。不可见 ID 查询主要覆盖已发布私有文档，旧索引块仍需状态校验或清理。|
|5 Spring|DocumentReviewServiceImpl.approveReview、ReviewOutboxService.enqueue|已有：事件写入要求已有本地事务，业务提交之后才触发审批通过的索引任务；通知提交后才尝试 WebSocket 推送。|用真实 Spring 代理和事务管理器验证 Outbox 插入失败时审核也回滚。直接 new 服务的单测不能覆盖代理。|
|6 多存储|DocumentContentServiceImpl.saveContent、getContentByDocumentId；DocumentServiceImpl.updateDocument|已有：正文写新 Mongo 记录，MySQL 事务切换有效指针；正文写失败终止元数据更新；回滚回调尽力清理新记录。|进程崩溃可能留下孤儿正文。清理前需确认当前指针、历史审核和版本引用，并给未提交事务留安全时间。|
|7 MySQL 索引|DocumentVersionMapper、ReviewOutboxService.deliver、sql/migrations|已有：迁移增加文档版本唯一键、Outbox 状态与重试时间索引。|用实际行数和任务积压量跑 EXPLAIN，再决定是否调整索引。没有实测扫描行数或耗时收益。|
|8 隔离与锁|DocumentVersionMapper.lockDocument、DocumentShareMapper.lockForAccess|已有：同一文档的版本号在行锁保护下分配。本轮：分享访问在事务中锁住分享记录，再校验有效性和额度。|同一热门分享可能排队，在 InnoDB 核对 share_id 索引和锁等待，再考虑条件 UPDATE。|
|9 审核状态机|DocumentReviewServiceImpl.submitForReview、approveReview、rejectReview|已有：锁文档、记录正文 ID；决定时校验版本，条件更新未处理记录与待审核文档；冲突抛异常。列表查询不再自动造审核记录。|迁移前已有的未绑定任务需要人工核对并重提，不能把当前正文当成当时送审的正文。|
|10 文档版本|DocumentVersionServiceImpl.createVersion、restoreVersion；DocumentServiceImpl.autoSaveDocument|已有：持久计数器替代记录数加一；编号与快照同事务；编辑、自动保存和恢复路径限制待审核修改。|两个客户端先后保存同一草稿仍可能覆盖对方。乐观锁需前端带回其读到的 revision，冲突后提供重新加载入口。|
|11 Redis 缓存|DocumentAclService.parseCached、safeGet、safePut|本轮：损坏的权限列表整体作废并回源；正式空集合只认哨兵；切换缓存键版本，避免沿用旧解析逻辑产生的数据。|60 秒内的合法旧缓存仍可能保留撤权前状态；主动失效需要覆盖公开性、归属变更与多实例。|
|12 RabbitMQ|ReviewOutboxService.enqueue、deliver；ReviewNotificationListener.handleReviewEvent|已有：审核同事务写 Outbox，租约领取，检查 ACK 及退回，失败可重试。Inbox 唯一键和通知同事务，重复投递不重复落库。|在真实 RabbitMQ 演练断连、未路由、确认后崩溃。积压告警与毒消息人工重放尚待建设。|
|13 Feign|DocumentAclProviderImpl.fetchInaccessibleDocumentIds、ReviewNotificationListener.findReviewerUserIds|本轮：权限接口异常响应不再部分采信。已有：审核收件人查询失败向外传播，让消费事务回滚。|按读写语义分配超时预算。写接口需要幂等条件才能重试，不能把所有超时都当作未执行。|
|14 文件与分享|FileManagementServiceImpl.streamFile、DocumentShareServiceImpl.accessShare|本轮：修复分享访问“先查额度再按旧值更新”的竞争。修改分享设置时也锁同一行，避免旧计数覆盖刚成功的访问；访问计数写入失败也不会返回成功；verifyShareAccess 只是预检查，实际消费以 accessShare 为准。|分享口令当前使用 MD5，可迁移到自适应哈希。需兼容历史口令并设计逐步升级策略。|
|15 索引与统计|DocumentReviewServiceImpl.approveReview、StatisticsEventPublisher.publishViewEvent|已有：审核通过后延至事务提交才开始建索引。审核通知 Outbox 的可靠重试范围仅覆盖通知。|索引/统计要支持重放，需分别定义文档版本、事件去重和重建方式，防止旧事件覆盖新状态。|
|16 实时通信|ReviewNotificationListener、WebSocketAuthInterceptor.preSend、AiWritingServiceImpl.generateStream|已有：通知持久化先于实时推送，离线可读。写作入口当前等待完整模型响应后再分块发送。|真正的模型流式输出需要可流式调用的客户端，以及 SSE 断连时取消上游生成。|
|17 限流|GatewayRateLimitFilter.filter|本轮：Redis 判定先完成，再执行下游链。放行只执行一次；拒绝写 429；空结果或 Redis 异常遵循配置；业务异常不会触发限流降级重发。|多实例窗口由应用时间参与计算，跨机时钟偏差值得在真实 Redis 压测后评估。|
|18 Java 并发|DocumentServiceImpl.autoSaveDocument、DocumentReviewServiceImpl 的异步索引方法|已有：审批索引启动时机后移；自动保存仍存在异步历史快照路径。|若历史快照不能丢，需要持久任务或事务后可靠投递；线程池容量和拒绝策略需先测任务时长与积压。|
|19 日志部署|GlobalLogFilter.filter、UnifiedResponseFilter.filter、AuthController.verifyEmail、UserServiceImpl.verifyEmail|本轮：网关访问日志只记路径，响应日志去掉 JSON 正文；认证入口不再记录 Token 前缀和邮箱激活令牌。保留 traceId 和响应状态供排查。|这里只覆盖已核实的日志点。代理访问日志、模型输入输出日志以及各部署平台仍需单独治理。|
|20 实战验证|GatewayBoundaryTest、ShareQuotaTest、此前的认证/审核/正文/通知测试|本轮：新增限流调用次数、身份头清理、ACL 回源和分享额度竞争回归。|完整联调应在独立库跑迁移，演练真实 Redis、InnoDB、MongoDB 和 RabbitMQ，再记录数据和延迟。|

## 如何核验本轮改动

- **限流。** 下游成功返回的 Mono<Void> 不产生数据元素。旧代码在业务链之后调用 switchIfEmpty，会将正常完成误认为 Redis 没有判定。测试统计下游订阅次数，同时检查 429 和异常传播。Redis 决策用 Mock 产生，执行的是实际 Reactor 过滤链。
- **权限。** 测试把缓存设置为含一个有效 ID 和一项损坏数据的字符串，权限源给出两个有效 ID，断言最终取齐两个。然后让权限源返回 null，断言 unresolved。显式关闭 ACL 的配置仍然放行；本轮未实现实时撤权。
- **分享。** 两个线程在同一起点访问同一分享链接，额度为 1。使用两个独立 JDBC 事务、生产锁查询 SQL 和实际 accessShare 方法，断言一个成功，一个达到上限，最终计数为 1。测试库为 H2 MySQL 兼容模式，Mapper 写入使用 JDBC 测试适配器；InnoDB 锁范围与性能需另测。
- **运行。** 在后端根目录执行 `mvn -B test`。H2 仅为测试依赖。本轮不新增部署迁移；此前版本号、审核及通知相关迁移仍需在目标环境执行。


## 源码与回归测试导航

- [kb-gateway · InternalHeaderStripFilter](kb-gateway/src/main/java/com/knowledge/base/gateway/filter/InternalHeaderStripFilter.java)
- [kb-gateway · AuthGlobalFilter](kb-gateway/src/main/java/com/knowledge/base/gateway/filter/AuthGlobalFilter.java)
- [kb-gateway · GatewayRateLimitFilter](kb-gateway/src/main/java/com/knowledge/base/gateway/filter/GatewayRateLimitFilter.java)
- [kb-common · DocumentAclService](kb-common/src/main/java/com/knowledge/base/common/acl/DocumentAclService.java)
- [kb-ai · DocumentAclProviderImpl](kb-ai/src/main/java/com/knowledge/base/ai/client/DocumentAclProviderImpl.java)
- [kb-search · DocumentAclProviderImpl](kb-search/src/main/java/com/knowledge/base/search/feign/DocumentAclProviderImpl.java)
- [kb-document · JwtAuthenticationFilter](kb-document/src/main/java/com/knowledge/base/document/filter/JwtAuthenticationFilter.java)
- [kb-foundation · JwtAuthenticationFilter](kb-foundation/src/main/java/com/knowledge/base/foundation/filter/JwtAuthenticationFilter.java)
- [kb-user-auth · JwtAuthenticationFilter](kb-user-auth/src/main/java/com/knowledge/base/userauth/filter/JwtAuthenticationFilter.java)
- [kb-document · DocumentShareServiceImpl](kb-document/src/main/java/com/knowledge/base/document/service/impl/DocumentShareServiceImpl.java)
- [kb-document · DocumentShareMapper](kb-document/src/main/java/com/knowledge/base/document/mapper/DocumentShareMapper.java)
- [kb-gateway · GlobalLogFilter](kb-gateway/src/main/java/com/knowledge/base/gateway/filter/GlobalLogFilter.java)
- [kb-gateway · UnifiedResponseFilter](kb-gateway/src/main/java/com/knowledge/base/gateway/filter/UnifiedResponseFilter.java)
- [kb-gateway · GatewayBoundaryTest](kb-gateway/src/test/java/com/knowledge/base/gateway/filter/GatewayBoundaryTest.java)
- [kb-document · ShareQuotaTest](kb-document/src/test/java/com/knowledge/base/document/service/impl/ShareQuotaTest.java)
- [kb-user-auth · RefreshSessionService](kb-user-auth/src/main/java/com/knowledge/base/userauth/service/RefreshSessionService.java)
- [kb-document · ReviewOutboxService](kb-document/src/main/java/com/knowledge/base/document/service/ReviewOutboxService.java)
- [kb-foundation · ReviewNotificationListener](kb-foundation/src/main/java/com/knowledge/base/foundation/mq/ReviewNotificationListener.java)
- [kb-document · DocumentReviewServiceImpl](kb-document/src/main/java/com/knowledge/base/document/service/impl/DocumentReviewServiceImpl.java)
- [kb-document · DocumentContentServiceImpl](kb-document/src/main/java/com/knowledge/base/document/service/impl/DocumentContentServiceImpl.java)
- [kb-document · DocumentVersionServiceImpl](kb-document/src/main/java/com/knowledge/base/document/service/impl/DocumentVersionServiceImpl.java)

本轮 Maven 全模块验证包含 33 项测试，其中新增 7 项。既有 26 项包含 5 项 PPTX XML 修复测试，不能把总数理解为 33 个端到端用例。
