package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.raise.either
import com.connor.domain.failure.PostError
import com.connor.domain.model.*
import com.connor.core.utlis.SnowflakeIdGenerator
import com.connor.domain.repository.PostRepository
import org.slf4j.LoggerFactory

/**
 * 创建 Post 的业务逻辑
 *
 * 职责：
 * - 验证业务规则（内容、媒体数量、父 Post 存在性）
 * - 编排领域对象
 * - 调用 Repository 持久化
 *
 * 注意：实时通知由 Route 层负责，避免在 Use Case 中额外查询数据库
 */
class CreatePostUseCase(
    private val postRepository: PostRepository
) {
    private val logger = LoggerFactory.getLogger(CreatePostUseCase::class.java)

    suspend operator fun invoke(cmd: CreatePostCommand): Either<PostError, Post> {
        logger.info("开始创建 Post: authorId=${cmd.authorId}, parentId=${cmd.parentId}")

        return either {
            // 1. 验证内容
            val content = PostContent(cmd.content).onLeft { error ->
                logger.warn("内容验证失败: error=$error")
            }.bind()

            // 2. 验证媒体数量
            if (cmd.mediaUrls.size > 4) {
                logger.warn("媒体数量超限: count=${cmd.mediaUrls.size}")
                raise(PostError.TooManyMedia(cmd.mediaUrls.size))
            }

            // 3. 验证并构造媒体附件
            val media = cmd.mediaUrls.mapIndexed { index, (url, type) ->
                val validatedUrl = MediaUrl(url).onLeft { error ->
                    logger.warn("媒体 URL 验证失败: url=$url, error=$error")
                }.bind()

                MediaAttachment(
                    url = validatedUrl,
                    type = type,
                    order = index
                )
            }

            // 4. 如果是回复，验证父 Post 存在
            if (cmd.parentId != null) {
                postRepository.findById(cmd.parentId).onLeft { error ->
                    logger.error("父 Post 不存在: parentId=${cmd.parentId}")
                    raise(PostError.ParentPostNotFound(cmd.parentId))
                }.bind()
            }

            // 5. 创建 Post 实体
            val postId = PostId(SnowflakeIdGenerator.nextId())
            val post = Post(
                id = postId,
                authorId = cmd.authorId,
                content = content,
                media = media,
                parentId = cmd.parentId
            )

            // 6. 持久化
            val savedPost = postRepository.create(post).onLeft { error ->
                logger.error("Post 保存失败: postId=${postId.value}, error=$error")
            }.bind()

            logger.info("Post 创建成功: postId=${savedPost.id.value}")
            savedPost
        }
    }
}

/**
 * 创建 Post 的命令对象
 * 解耦 HTTP Request 和 Domain 层
 */
data class CreatePostCommand(
    val authorId: UserId,
    val content: String,
    val mediaUrls: List<Pair<String, MediaType>> = emptyList(), // (url, type) pairs
    val parentId: PostId? = null // null = 顶层 Post，非 null = 回复
)
