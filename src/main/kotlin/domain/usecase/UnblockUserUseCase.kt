package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.left
import com.connor.domain.failure.UserError
import com.connor.domain.model.UserId
import com.connor.domain.repository.UserRepository
import org.slf4j.LoggerFactory

class UnblockUserUseCase(
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(UnblockUserUseCase::class.java)

    suspend operator fun invoke(blockerId: UserId, blockedId: UserId): Either<UserError, Unit> {
        logger.info("取消拉黑: blockerId=${blockerId.value}, blockedId=${blockedId.value}")

        if (blockerId == blockedId) {
            logger.warn("尝试取消拉黑自己: userId=${blockerId.value}")
            return UserError.CannotBlockSelf.left()
        }

        return userRepository.unblock(blockerId, blockedId)
    }
}
