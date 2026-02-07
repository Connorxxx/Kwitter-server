package com.connor.features.media

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

private val logger = LoggerFactory.getLogger("MediaRoutes")

private val ALLOWED_CONTENT_TYPES = setOf(
    "image/jpeg",
    "image/png",
    "image/webp",
    "video/mp4"
)

private const val MAX_FILE_SIZE = 10 * 1024 * 1024L // 10MB

private fun contentTypeToMediaType(contentType: String): String = when {
    contentType.startsWith("image/") -> "IMAGE"
    contentType.startsWith("video/") -> "VIDEO"
    else -> "IMAGE"
}

private fun contentTypeToExtension(contentType: String): String = when (contentType) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "video/mp4" -> "mp4"
    else -> "bin"
}

fun Route.mediaRoutes(uploadDir: String) {
    route("/v1/media") {
        authenticate("auth-jwt") {
            post("/upload") {
                val startTime = System.currentTimeMillis()

                val uploadsDir = File(uploadDir)
                if (!uploadsDir.exists()) {
                    uploadsDir.mkdirs()
                }

                val multipart = call.receiveMultipart()
                var mediaUploadResponse: MediaUploadResponse? = null

                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            val originalFileName = part.originalFileName ?: "unknown"
                            val contentType = part.contentType?.toString() ?: ""

                            if (contentType !in ALLOWED_CONTENT_TYPES) {
                                part.dispose()
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to "Unsupported file type: $contentType. Allowed: $ALLOWED_CONTENT_TYPES")
                                )
                                return@forEachPart
                            }

                            val bytes = part.provider().readRemaining().readByteArray()

                            if (bytes.size > MAX_FILE_SIZE) {
                                part.dispose()
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to "File too large. Maximum size: 10MB")
                                )
                                return@forEachPart
                            }

                            val extension = contentTypeToExtension(contentType)
                            val uuid = UUID.randomUUID().toString()
                            val fileName = "$uuid.$extension"
                            val file = File(uploadsDir, fileName)
                            file.writeBytes(bytes)

                            val mediaType = contentTypeToMediaType(contentType)
                            mediaUploadResponse = MediaUploadResponse(
                                url = "/uploads/$fileName",
                                type = mediaType
                            )

                            val duration = System.currentTimeMillis() - startTime
                            logger.info(
                                "Media uploaded: name=$originalFileName, size=${bytes.size}, " +
                                "type=$mediaType, file=$fileName, duration=${duration}ms"
                            )
                        }
                        else -> {}
                    }
                    part.dispose()
                }

                if (mediaUploadResponse != null) {
                    call.respond(HttpStatusCode.Created, mediaUploadResponse!!)
                } else {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "No file provided")
                    )
                }
            }
        }
    }
}
