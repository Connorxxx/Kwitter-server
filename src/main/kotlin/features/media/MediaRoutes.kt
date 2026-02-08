package com.connor.features.media

import com.connor.domain.usecase.UploadMediaUseCase
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("MediaRoutes")

/**
 * 媒体路由 - 处理文件上传
 *
 * 架构特点：
 * - 只负责 HTTP 协议转换，不包含业务逻辑
 * - 业务逻辑由 UploadMediaUseCase 处理
 * - 错误映射由 MediaMappers 处理
 *
 * @param uploadMediaUseCase 注入的 UseCase，处理验证和存储
 */
fun Route.mediaRoutes(uploadMediaUseCase: UploadMediaUseCase) {
    route("/v1/media") {
        authenticate("auth-jwt") {
            post("/upload") {
                val startTime = System.currentTimeMillis()

                try {
                    // 接收 multipart 请求
                    val multipart = call.receiveMultipart()

                    // 遍历 multipart，处理第一个文件
                    // （单文件上传模式，处理后立即返回，避免多次 respond）
                    var part: PartData? = multipart.readPart()
                    while (part != null) {
                        when (part) {
                            is PartData.FileItem -> {
                                val originalFileName = part.originalFileName ?: "unknown"
                                val contentType = part.contentType?.toString() ?: ""

                                logger.debug("Processing upload: fileName=$originalFileName, contentType=$contentType")

                                // 读取文件内容
                                val bytes = part.provider().readRemaining().readByteArray()

                                // 创建 UseCase 命令
                                val command = createUploadMediaCommand(
                                    fileName = originalFileName,
                                    contentType = contentType,
                                    fileBytes = bytes
                                )

                                // 调用 UseCase
                                val result = uploadMediaUseCase(command)
                                val duration = System.currentTimeMillis() - startTime

                                // 处理结果并响应客户端
                                result.fold(
                                    ifLeft = { error ->
                                        val (statusCode, errorResponse) = error.toHttpResponse()
                                        logger.warn("Upload validation failed: error=$error, duration=${duration}ms")
                                        call.respond(statusCode, errorResponse)
                                    },
                                    ifRight = { uploaded ->
                                        val response = uploaded.toResponse()
                                        logger.info(
                                            "Media uploaded successfully: name=$originalFileName, " +
                                            "size=${bytes.size}, type=${uploaded.type}, " +
                                            "url=${uploaded.url.value}, duration=${duration}ms"
                                        )
                                        // 响应客户端
                                        call.respond(HttpStatusCode.Created, response)
                                    }
                                )

                                // 处理完第一个文件后立即返回，避免多次 respond
                                part.dispose()
                                return@post
                            }

                            else -> {
                                // 忽略其他 part 类型（如表单字段）
                                logger.debug("Skipping non-file part: ${part.javaClass.simpleName}")
                            }
                        }

                        // 推进到下一个 part
                        part.dispose()
                        part = multipart.readPart()
                    }

                    // 没有找到任何文件
                    val duration = System.currentTimeMillis() - startTime
                    logger.warn("No file provided in upload request, duration=${duration}ms")
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(error = "No file provided")
                    )
                } catch (e: Exception) {
                    logger.error("Unexpected error during media upload", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(
                            error = "Upload failed",
                            details = e.message
                        )
                    )
                }
            }
        }
    }
}
