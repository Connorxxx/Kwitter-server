package com.connor.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.connor.domain.failure.AuthError
import com.connor.domain.model.RefreshToken
import com.connor.domain.model.UserId
import com.connor.domain.repository.RefreshTokenRepository
import com.connor.domain.repository.UserRepository
import com.connor.domain.service.AuthTokenConfig
import com.connor.domain.service.SessionNotifier
import com.connor.domain.service.TokenHasher
import com.connor.domain.service.TokenIssuer
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Token 刷新结果
 */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long
)

/**
 * Refresh Token Use Case
 *
 * 流程：
 * 1. 客户端 JWT 过期 → 401
 * 2. 客户端用 refresh token 请求 /v1/auth/refresh
 * 3. 验证 refresh token → Token Rotation → 返回新 JWT + 新 refresh token
 *
 * 安全机制：
 * - Token Family: 同一登录会话的所有轮换 token 共享 familyId
 * - Reuse Detection: 旧 token 被重用 → 整个 family 撤销 + WebSocket 通知强制下线
 * - Grace Period: 10秒内的并发刷新请求不触发 reuse detection
 */
class RefreshTokenUseCase(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val userRepository: UserRepository,
    private val tokenIssuer: TokenIssuer,
    private val tokenHasher: TokenHasher,
    private val authTokenConfig: AuthTokenConfig,
    private val sessionNotifier: SessionNotifier
) {
    private val logger = LoggerFactory.getLogger(RefreshTokenUseCase::class.java)

    /**
     * 刷新 token
     */
    suspend operator fun invoke(rawRefreshToken: String): Either<AuthError, TokenPair> {
        val tokenHash = tokenHasher.hashToken(rawRefreshToken)
        val storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
            ?: return AuthError.RefreshTokenNotFound.left()

        // 检查是否过期
        if (storedToken.isExpired) {
            logger.info("Refresh token expired: userId={}, familyId={}", storedToken.userId.value, storedToken.familyId)
            return AuthError.RefreshTokenExpired.left()
        }

        // 检查是否已撤销（可能是 reuse attack）
        if (storedToken.isRevoked) {
            return handlePossibleReuse(storedToken)
        }

        // 原子轮换：仅当 token 仍为 active 时才撤销（防止并发竞态）
        val wasActive = refreshTokenRepository.revokeIfActive(tokenHash)
        if (!wasActive) {
            // 另一个并发请求已经完成了轮换，按 reuse 路径处理
            return handlePossibleReuse(storedToken)
        }

        return issueNewTokenPair(storedToken.userId, storedToken.familyId)
    }

    /**
     * 登录/注册时创建初始 token pair
     */
    suspend fun createInitialTokenPair(userId: UserId, displayName: String, username: String): TokenPair {
        val familyId = UUID.randomUUID().toString()
        val accessToken = tokenIssuer.generate(userId.value, displayName, username)
        val refreshToken = createAndSaveRefreshToken(userId, familyId)

        return TokenPair(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = authTokenConfig.accessTokenExpiresInMs
        )
    }

    /**
     * 撤销用户所有 refresh token（密码修改、强制登出等）
     */
    suspend fun revokeAllForUser(userId: UserId) {
        refreshTokenRepository.revokeAllForUser(userId)
        sessionNotifier.notifySessionRevoked(
            userId,
            """{"type":"auth_revoked","message":"会话已失效，请重新登录"}"""
        )
        logger.info("All tokens revoked and user notified: userId={}", userId.value)
    }

    /**
     * 处理可能的 token reuse
     *
     * 已撤销的 token 被使用可能是：
     * 1. 并发请求（grace period 内） → 返回 StaleRefreshToken，客户端应使用最新本地 token 重试
     * 2. Token 被盗用（replay attack） → 撤销整个 family + 通知
     *
     * 关键设计：宽限期内不再签发新 token pair，保持"单 family 单 active token"不变量。
     */
    private suspend fun handlePossibleReuse(storedToken: RefreshToken): Either<AuthError, TokenPair> {
        val now = System.currentTimeMillis()
        val latestRevoked = refreshTokenRepository.findLatestRevokedInFamily(storedToken.familyId)

        // Grace period: 如果 token 是在宽限期内被撤销的，视为并发请求
        if (latestRevoked?.revokedAt != null) {
            val timeSinceRevoked = now - latestRevoked.revokedAt
            if (timeSinceRevoked <= authTokenConfig.refreshTokenGracePeriodMs) {
                logger.info(
                    "Grace period hit (stale token): userId={}, familyId={}, timeSinceRevoked={}ms",
                    storedToken.userId.value, storedToken.familyId, timeSinceRevoked
                )
                return AuthError.StaleRefreshToken.left()
            }
        }

        // Reuse Detection: 超出宽限期，视为攻击 → 撤销整个 family
        logger.warn(
            "Token reuse detected! Revoking entire family: userId={}, familyId={}",
            storedToken.userId.value, storedToken.familyId
        )
        refreshTokenRepository.revokeFamily(storedToken.familyId)

        // 通知用户强制下线
        sessionNotifier.notifySessionRevoked(
            storedToken.userId,
            """{"type":"auth_revoked","message":"检测到异常登录，会话已失效，请重新登录"}"""
        )

        return AuthError.TokenFamilyReused.left()
    }

    private suspend fun issueNewTokenPair(userId: UserId, familyId: String): Either<AuthError, TokenPair> {
        // 查询用户最新信息以生成新 JWT
        val user = userRepository.findById(userId).getOrNull()
            ?: return AuthError.RefreshTokenNotFound.left()

        val accessToken = tokenIssuer.generate(
            userId = user.id.value,
            displayName = user.displayName.value,
            username = user.username.value
        )
        val newRefreshToken = createAndSaveRefreshToken(userId, familyId)

        return TokenPair(
            accessToken = accessToken,
            refreshToken = newRefreshToken,
            expiresIn = authTokenConfig.accessTokenExpiresInMs
        ).right()
    }

    private suspend fun createAndSaveRefreshToken(userId: UserId, familyId: String): String {
        val rawToken = tokenHasher.generateToken()
        val tokenHash = tokenHasher.hashToken(rawToken)

        val refreshToken = RefreshToken(
            id = UUID.randomUUID().toString(),
            tokenHash = tokenHash,
            userId = userId,
            familyId = familyId,
            expiresAt = System.currentTimeMillis() + authTokenConfig.refreshTokenExpiresInMs
        )

        refreshTokenRepository.save(refreshToken)
        return rawToken
    }
}
