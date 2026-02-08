# Post 模块重构评审（含 Media / Likes / Bookmarks 关联）

更新时间：2026-02-08  
评审范围：
- `features/post/*`
- `domain/usecase/*WithStatusUseCase*`、`CreatePostUseCase`
- `data/repository/ExposedPostRepository.kt`
- `data/db/schema/PostsTable.kt`
- 关联模块：`features/media/*`、`docs/likes-bookmarks-design.md`

约束：
- 以“简单、实用、可落地”为优先，不为理论纯洁做大手术。
- 仅提出真实风险与高收益改动，避免过度工程化。

---

## 1) 设计哲学融合（先对齐思路）

你提供的 `docs/auth-refactor-final-decision.md` 里有一个核心点我认同：  
**优先修真实架构违规，不做“看起来优雅但无收益”的改造。**

本次 Post 评审我采用同一原则：
1. 先修安全/数据正确性问题（P0/P1）。
2. 再修性能瓶颈（可量化的 N+1、索引缺失）。
3. 最后做结构收敛（减少重复、统一错误模型）。
4. 不在本轮拆微服务、不引入新框架、不搞抽象体操。

---

## 2) 结论摘要

Post 模块目前“能跑”，但存在 5 个必须优先处理的问题：

1. **作者邮箱被公开暴露**（时间线等公开接口返回作者 email）。
2. **书签列表权限边界不清**（设计说“私有数据”，实现却允许任意访客查询他人 bookmarks）。
3. **列表查询存在明显 N+1**（每条 Post 单独查 media）。
4. **关键查询缺索引**（`authorId/parentId/createdAt`，规模上来后会直接拖垮）。
5. **创建 Post 响应契约不稳定**（异常分支返回 `mapOf("postId")`，与主响应结构不一致）。

---

## 3) Findings（按严重级别）

### P0-1 公开接口泄露作者邮箱（安全/隐私）

证据：
- `src/main/kotlin/features/post/PostSchema.kt:63`
- `src/main/kotlin/features/post/PostSchema.kt:66`
- `src/main/kotlin/features/post/PostMappers.kt:107`
- `src/main/kotlin/features/post/PostMappers.kt:111`
- `src/main/kotlin/features/post/PostRoutes.kt:36`
- `src/main/kotlin/features/post/PostRoutes.kt:43`

问题：
- `AuthorDto` 包含 `email`，并在公开可访问的 timeline/replies/user-posts 等接口中返回。

风险：
- 用户隐私直接泄露；如果上层产品不允许公开邮箱，这是直接合规问题。

建议（必须做）：
- 从 `AuthorDto` 去掉 `email`。
- 若确有后台管理需求，提供受保护后台 DTO，不混用公开 DTO。

---

### P0-2 Bookmark 可见性与设计哲学冲突（权限边界）

证据：
- `docs/likes-bookmarks-design.md:203`（“Bookmark 是用户私有数据”）
- `src/main/kotlin/features/post/BookmarkRoutes.kt:99`
- `src/main/kotlin/features/post/BookmarkRoutes.kt:101`
- `src/main/kotlin/features/post/BookmarkRoutes.kt:103`

问题：
- `/v1/users/{userId}/bookmarks` 在 `authenticateOptional` 下开放，访客可读取任意用户收藏列表。

风险：
- 业务语义冲突：文档定义私有，代码实现公开。
- 客户端会形成错误依赖，后续改权限会变成破坏性变更。

建议（必须做）：
- 二选一并明确写进文档：
1. **私有模式（推荐）**：改为 `authenticate("auth-jwt")`，仅允许 `currentUserId == path userId`。
2. 公开模式：修正文档与产品规则，承认公开收藏行为。

---

### P1-1 ExposedPostRepository 存在 N+1 查询（性能）

证据：
- `src/main/kotlin/data/repository/ExposedPostRepository.kt:149`
- `src/main/kotlin/data/repository/ExposedPostRepository.kt:171`
- `src/main/kotlin/data/repository/ExposedPostRepository.kt:189`
- `src/main/kotlin/data/repository/ExposedPostRepository.kt:211`
- `src/main/kotlin/data/repository/ExposedPostRepository.kt:213`
- `src/main/kotlin/data/repository/ExposedPostRepository.kt:404`
- `src/main/kotlin/data/repository/ExposedPostRepository.kt:552`

问题：
- 列表先查 posts，再在 `toPostDetailWithMedia()` 里按每条 post 单独查 media。
- timeline/replies/user-posts/user-likes/user-bookmarks 都会放大这个成本。

风险：
- 数据量增加后，SQL 次数线性膨胀，接口延迟和 DB 压力明显上升。

建议（必须做）：
- 改成“两段式批量查询”：
1. 一次查出当前页所有 posts（含 author/stats）。
2. 用 `postIds IN (...)` 一次查全部 media，再在内存按 `postId` 分组组装。

---

### P1-2 关键查询路径缺索引（性能）

证据：
- `src/main/kotlin/data/db/schema/PostsTable.kt:12`
- `src/main/kotlin/data/db/schema/PostsTable.kt:17`
- `src/main/kotlin/data/db/schema/PostsTable.kt:20`
- `src/main/kotlin/data/db/schema/PostsTable.kt:24`
- 查询使用处：
  - `src/main/kotlin/data/repository/ExposedPostRepository.kt:154`
  - `src/main/kotlin/data/repository/ExposedPostRepository.kt:177`
  - `src/main/kotlin/data/repository/ExposedPostRepository.kt:197`

问题：
- 常用过滤与排序字段（`author_id`, `parent_id`, `created_at`）未建立索引。

风险：
- 查询性能会随数据增长快速恶化，分页接口最先受影响。

建议（必须做）：
- 增加索引（至少）：
1. `idx_posts_parent_created (parent_id, created_at)`
2. `idx_posts_author_parent_created (author_id, parent_id, created_at)`
3. `idx_posts_created (created_at)`（视查询计划决定）

---

### P1-3 创建 Post 响应契约不稳定（API 正确性）

证据：
- `src/main/kotlin/features/post/PostRoutes.kt:359`
- `src/main/kotlin/features/post/PostRoutes.kt:365`

问题：
- 创建成功后先二次查询详情；若二次查询失败，返回 `mapOf("postId")`。
- 同一接口可能返回两种不同 JSON 结构。

风险：
- 客户端解析模型不稳定，线上问题难排查。

建议（必须做）：
- 保证接口响应类型固定：
1. 成功固定 `PostDetailResponse`。
2. 若详情查询失败，返回明确错误（500）而非降级成临时 map。

---

### P1-4 Route 层大量 `try/catch(Exception)+throw` 与全局异常重复

证据：
- `src/main/kotlin/features/post/PostRoutes.kt:49`
- `src/main/kotlin/features/post/PostRoutes.kt:100`
- `src/main/kotlin/features/post/PostRoutes.kt:149`
- `src/main/kotlin/features/post/PostRoutes.kt:372`
- `src/main/kotlin/features/post/LikeRoutes.kt:116`
- `src/main/kotlin/features/post/BookmarkRoutes.kt:115`
- 全局处理：`src/main/kotlin/plugins/StatusPages.kt:38`

问题：
- Route 已捕获并记录异常后再抛出，全局 StatusPages 还会再记录一次。

风险：
- 日志重复、噪音高；定位时容易误判异常次数。

建议（必须做）：
- 与 Auth 模块同样策略：
1. Route 仅处理 `Either` 业务错误。
2. 非预期异常统一交给 `StatusPages`。

---

### P2-1 交互状态 UseCase 与 Route 代码重复过高（维护成本）

证据：
- `src/main/kotlin/domain/usecase/GetTimelineWithStatusUseCase.kt:53`
- `src/main/kotlin/domain/usecase/GetRepliesWithStatusUseCase.kt:54`
- `src/main/kotlin/domain/usecase/GetUserPostsWithStatusUseCase.kt:54`
- `src/main/kotlin/domain/usecase/GetUserLikesWithStatusUseCase.kt:54`
- `src/main/kotlin/domain/usecase/GetUserBookmarksWithStatusUseCase.kt:54`
- Route 侧相似重复：
  - `src/main/kotlin/features/post/PostRoutes.kt:60`
  - `src/main/kotlin/features/post/LikeRoutes.kt:127`
  - `src/main/kotlin/features/post/BookmarkRoutes.kt:126`

问题：
- 5 套几乎一致的“收集列表 -> 批量查状态 -> emit Either”逻辑。
- Route 再做一次 Left/Right 拆分 + 强转。

风险：
- 改一个逻辑容易漏另四个，长期演化风险高。

建议（建议做）：
- 抽一个小型通用组件，不引入复杂框架：
1. `InteractionStatusAssembler`（输入 posts + currentUserId，输出 enriched list）。
2. UseCase 返回 `Either<Error, List<Item>>`，Route 不再做泛型强转。

---

### P2-2 分页 `hasMore` 计算不准确

证据：
- `src/main/kotlin/features/post/PostRoutes.kt:96`
- `src/main/kotlin/features/post/PostRoutes.kt:218`
- `src/main/kotlin/features/post/PostRoutes.kt:291`
- `src/main/kotlin/features/post/LikeRoutes.kt:163`
- `src/main/kotlin/features/post/BookmarkRoutes.kt:162`

问题：
- 目前 `hasMore = size == limit`，无法区分“刚好最后一页”等情况。

建议（建议做）：
- 查询 `limit + 1` 条，再裁剪到 `limit`，`hasMore = rawSize > limit`。

---

### P2-3 错误响应模型仍在 feature 内重复定义（跨模块一致性）

证据：
- 全局已存在：`src/main/kotlin/core/http/ApiErrorResponse.kt:12`
- Post 本地定义：`src/main/kotlin/features/post/PostMappers.kt:199`
- Media 本地定义：`src/main/kotlin/features/media/MediaMappers.kt:35`

问题：
- 错误响应格式在多个 feature 内重复，后续统一治理成本高。

建议（建议做）：
- 统一成 `core/http` 错误模型（可保留 feature code/message 但结构统一）。

---

### P3-1 明显未使用依赖与参数（清理项）

证据：
- `src/main/kotlin/features/post/PostRoutes.kt:25`
- `src/main/kotlin/features/post/PostRoutes.kt:27`
- `src/main/kotlin/features/post/LikeRoutes.kt:30`
- `src/main/kotlin/features/post/BookmarkRoutes.kt:29`
- `src/main/kotlin/data/repository/ExposedPostRepository.kt:21`

问题：
- 注入/参数未使用，增加阅读成本并误导后续开发。

建议（可顺手做）：
- 删除未使用参数和导入，简化构造函数与路由签名。

---

## 4) 分模块重构方案（实用版）

### 4.1 Transport（Post/Like/Bookmark）

目标：协议层薄化，契约稳定。

改造：
1. 删除 Route 通用 `catch(Exception)`，统一交给 `StatusPages`。  
2. `POST /v1/posts` 保证固定返回 `PostDetailResponse`，去掉 fallback map。  
3. `AuthorDto` 删除 `email`。  
4. `GET /v1/users/{userId}/bookmarks` 明确权限策略（推荐私有）。  
5. 分页统一改为 `limit + 1` 计算 `hasMore`。

---

### 4.2 Application/UseCase（交互状态）

目标：去重复，降低维护风险。

改造：
1. 抽出通用“批量交互状态附着器”函数（单文件 helper 足够）。  
2. 把 `Flow<Either<...>>` 收敛为 `Either<..., List<...>>`（或直接 `List` + 异常路径），减少 Route 强转。  
3. 保留你们当前“强一致性失败即失败”的策略，但集中在一处实现。

---

### 4.3 Infrastructure（Exposed + Schema）

目标：性能与数据正确性。

改造：
1. 列表查询改“post 批量 + media 批量”，去掉 `toPostDetailWithMedia()` 的逐条媒体查询。  
2. 为 posts 核心查询路径加索引。  
3. （若后续启用删除）实现删除的完整一致性策略：权限校验、计数回写、关联数据处理。

---

### 4.4 跨模块（Media / 错误模型）

目标：减少风格漂移。

改造：
1. Post/Media 统一错误响应结构（`core/http`）。  
2. 保持媒体上传链路与 Post 引用契约一致（可在后续加“媒体归属校验”，本轮可不做）。

---

## 5) 推荐实施顺序

### 迭代 A（必须做，先消风险）

1. 隐私与权限：去掉作者 email，收紧 bookmarks 可见性。  
2. Post 创建响应契约固定化。  
3. Route 去掉重复异常捕获。  

### 迭代 B（高收益性能）

1. 修复 N+1（批量拉媒体）。  
2. 增加 posts 索引。  
3. 分页 `hasMore` 改 `limit+1`。

### 迭代 C（维护性）

1. 合并 5 套 with-status 逻辑。  
2. 统一错误响应模型。  
3. 清理未使用注入参数/导入。

---

## 6) 验收标准（可打勾）

1. 公开 Post 相关响应中不再出现作者 email。  
2. bookmarks 可见性与文档一致（私有或公开，二者统一）。  
3. `/v1/posts` 创建成功响应结构稳定且唯一。  
4. timeline/replies/userPosts/userLikes/userBookmarks 不再出现 per-post 媒体查询。  
5. `posts` 表存在覆盖主查询路径的索引。  
6. 业务错误与系统异常日志不重复记录。  
7. `hasMore` 在边界页行为正确。

---

## 7) 明确不做（防过度工程化）

1. 不拆微服务。  
2. 不引入新的 ORM/框架。  
3. 不为了“接口纯净”立即拆分多个 repository 实现。  
4. 不做全链路重写（保持 API 路径和主体契约兼容）。

---

## 8) 结语

这轮 Post 重构的关键不是“更花哨的分层”，而是把边界收紧到可长期维护：  
**安全（隐私/权限）先行，性能（N+1/索引）其次，结构（去重复）最后。**
