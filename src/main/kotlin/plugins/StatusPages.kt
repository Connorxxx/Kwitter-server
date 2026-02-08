package com.connor.plugins

import com.connor.features.auth.ErrorResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("StatusPages")

/**
 * 判断是否为生产环境
 */
private fun isDevelopment(): Boolean {
    return System.getenv("ENVIRONMENT")?.lowercase() != "production" &&
           System.getenv("PROFILE")?.lowercase() != "prod"
}

/**
 * 根据环境返回错误详情
 * - 开发环境：包含详细信息便于调试
 * - 生产环境：仅返回通用消息
 */
private fun getErrorMessage(defaultMessage: String, detailMessage: String? = null): String {
    return if (isDevelopment()) {
        detailMessage ?: defaultMessage
    } else {
        defaultMessage
    }
}

/**
 * 全局异常处理器
 * 捕获所有未处理的异常，记录日志并返回标准错误响应
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        // 处理通用异常
        exception<Throwable> { call, cause ->
            val clientIp = call.request.local.remoteAddress
            val path = call.request.uri
            val method = call.request.httpMethod.value

            logger.error(
                "未捕获异常: method=$method, path=$path, clientIp=$clientIp, " +
                "error=${cause.javaClass.simpleName}, message=${cause.message}",
                cause
            )

            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    code = "INTERNAL_ERROR",
                    message = "服务器内部错误，请稍后重试"
                )
            )
        }

        // 处理 JSON 反序列化错误
        exception<kotlinx.serialization.SerializationException> { call, cause ->
            val clientIp = call.request.local.remoteAddress
            val path = call.request.uri

            logger.warn(
                "JSON 反序列化错误: path=$path, clientIp=$clientIp, " +
                "error=${cause.message}"
            )

            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    code = "INVALID_JSON",
                    message = getErrorMessage(
                        defaultMessage = "请求数据格式错误",
                        detailMessage = "请求数据格式错误: ${cause.message}"
                    )
                )
            )
        }

        // 处理 Content Type 错误
        exception<io.ktor.server.plugins.BadRequestException> { call, cause ->
            val clientIp = call.request.local.remoteAddress
            val path = call.request.uri

            logger.warn(
                "错误的请求: path=$path, clientIp=$clientIp, error=${cause.message}"
            )

            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    code = "BAD_REQUEST",
                    message = getErrorMessage(
                        defaultMessage = "请求格式错误",
                        detailMessage = "请求格式错误: ${cause.message}"
                    )
                )
            )
        }

        // 处理 404 Not Found
        status(HttpStatusCode.NotFound) { call, status ->
            val path = call.request.uri
            val clientIp = call.request.local.remoteAddress

            logger.warn("路径不存在: path=$path, clientIp=$clientIp")

            call.respond(
                status,
                ErrorResponse(
                    code = "NOT_FOUND",
                    message = "请求的资源不存在: $path"
                )
            )
        }

        // 处理 405 Method Not Allowed
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            val path = call.request.uri
            val method = call.request.httpMethod.value
            val clientIp = call.request.local.remoteAddress

            logger.warn("不支持的 HTTP 方法: method=$method, path=$path, clientIp=$clientIp")

            call.respond(
                status,
                ErrorResponse(
                    code = "METHOD_NOT_ALLOWED",
                    message = "不支持的 HTTP 方法: $method"
                )
            )
        }
    }
}
