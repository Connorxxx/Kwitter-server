package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.flatMap
import com.connor.domain.failure.UserError
import com.connor.domain.model.Block
import com.connor.domain.model.UserId
import com.connor.domain.repository.UserRepository
import org.slf4j.LoggerFactory

class BlockUserUseCase(
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(BlockUserUseCase::class.java)

    suspend operator fun invoke(blockerId: UserId, blockedId: UserId): Either<UserError, Block> {
        logger.info("拉黑用户: blockerId=${blockerId.value}, blockedId=${blockedId.value}")

        if (blockerId == blockedId) {
            logger.warn("尝试拉黑自己: userId=${blockerId.value}")
            return UserError.CannotBlockSelf.left()
        }

        return userRepository.block(blockerId, blockedId).also { result ->
            result.onRight {
                // Auto-unfollow both directions (ignore NotFollowing errors)
                userRepository.unfollow(blockerId, blockedId)
                userRepository.unfollow(blockedId, blockerId)
                logger.info("已自动解除双向关注: blockerId=${blockerId.value}, blockedId=${blockedId.value}")
            }
        }
    }
}
