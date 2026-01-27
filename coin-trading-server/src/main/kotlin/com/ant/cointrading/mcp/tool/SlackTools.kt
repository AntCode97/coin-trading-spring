package com.ant.cointrading.mcp.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springaicommunity.mcp.annotation.McpTool
import org.springaicommunity.mcp.annotation.McpToolParam
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant

/**
 * Slack MCP Tool
 *
 * Slack Bot Token을 사용하여:
 * 1. 채널 목록 조회
 * 2. 메시지 읽기
 * 3. 메시지 보내기
 *
 * 필수 Bot Scope:
 * - channels:history - 채널 메시지 읽기
 * - channels:read - 채널 목록 조회
 * - chat:write - 메시지 보내기
 * - groups:history - 프라이빗 채널 메시지 읽기
 * - groups:read - 프라이빗 채널 목록 조회
 * - im:history - DM 읽기
 * - im:read - DM 목록 조회
 */
@Component
class SlackTools(
    @Qualifier("slackRestClient") private val restClient: RestClient,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(SlackTools::class.java)

    @Value("\${slack.token:}")
    private lateinit var token: String

    private val baseUrl = "https://slack.com/api"

    // ===========================================
    // 채널 관리
    // ===========================================

    @McpTool(description = """
        Slack 워크스페이스의 모든 공개 채널 목록을 조회합니다.

        반환 정보:
        - 채널 ID
        - 채널 이름
        - 채널 설명
        - 채널 생성일
        - 멤버 수

        참고: Bot에 channels:read scope가 필요합니다.
    """)
    @Tool(description = """
        Slack 워크스페이스의 모든 공개 채널 목록을 조회합니다.

        반환 정보:
        - 채널 ID
        - 채널 이름
        - 채널 설명
        - 채널 생성일
        - 멤버 수

        참고: Bot에 channels:read scope가 필요합니다.
    """)
    fun listChannels(): String {
        if (token.isBlank()) {
            return "오류: Slack Token이 설정되지 않았습니다."
        }

        log.info("[Tool] listChannels 호출")

        return try {
            val response = restClient.get()
                .uri("$baseUrl/conversations.list")
                .header("Authorization", "Bearer $token")
                .retrieve()
                .body(String::class.java)

            val json = objectMapper.readTree(response)

            if (!json.get("ok").asBoolean()) {
                val error = json.get("error").asText()
                return "채널 목록 조회 실패: $error\n\nBot에 channels:read scope가 필요합니다."
            }

            val channels = json.get("channels")
            val result = StringBuilder("=== Slack 채널 목록 ===\n\n")

            channels.forEach { channel ->
                val id = channel.get("id").asText()
                val name = channel.get("name").asText()
                val isPrivate = channel.has("is_private") && channel.get("is_private").asBoolean()
                val isMember = channel.has("is_member") && channel.get("is_member").asBoolean()
                val created = channel.get("created").asLong()
                val createdDate = Instant.ofEpochSecond(created)

                val type = when {
                    isPrivate -> "[프라이빗]"
                    isMember -> "[공개-멤버]"
                    else -> "[공개]"
                }

                result.append("""
                    $type #$name
                    ID: $id
                    생성일: $createdDate
                """.trimIndent()).append("\n\n")
            }

            result.toString()

        } catch (e: Exception) {
            log.error("listChannels 실패: ${e.message}", e)
            "오류: ${e.message}"
        }
    }

    @McpTool(description = """
        Slack 워크스페이스의 모든 채널(공개, 프라이빗, DM) 목록을 조회합니다.

        반환 정보:
        - 공개 채널 (channels)
        - 프라이빗 채널 (groups)
        - DM (ims)

        참고: Bot에 channels:read, groups:read, im:read scope가 필요합니다.
    """)
    @Tool(description = """
        Slack 워크스페이스의 모든 채널(공개, 프라이빗, DM) 목록을 조회합니다.

        반환 정보:
        - 공개 채널 (channels)
        - 프라이빗 채널 (groups)
        - DM (ims)

        참고: Bot에 channels:read, groups:read, im:read scope가 필요합니다.
    """)
    fun listAllConversations(): String {
        if (token.isBlank()) {
            return "오류: Slack Token이 설정되지 않았습니다."
        }

        log.info("[Tool] listAllConversations 호출")

        return try {
            val result = StringBuilder("=== Slack 전체 대화 목록 ===\n\n")

            // 공개 채널
            result.append("### 공개 채널 ###\n")
            val publicChannels = fetchConversations("public_channel")
            result.append(publicChannels).append("\n")

            // 프라이빗 채널
            result.append("### 프라이빗 채널 ###\n")
            val privateChannels = fetchConversations("private_channel")
            result.append(privateChannels).append("\n")

            // DM
            result.append("### DM ###\n")
            val dms = fetchConversations("im")
            result.append(dms)

            result.toString()

        } catch (e: Exception) {
            log.error("listAllConversations 실패: ${e.message}", e)
            "오류: ${e.message}"
        }
    }

    private fun fetchConversations(types: String): String {
        return try {
            val response = restClient.get()
                .uri("$baseUrl/conversations.list?types=$types")
                .header("Authorization", "Bearer $token")
                .retrieve()
                .body(String::class.java)

            val json = objectMapper.readTree(response)

            if (!json.get("ok").asBoolean()) {
                return "조회 실패: ${json.get("error").asText()}\n"
            }

            val channels = json.get("channels")
            if (!channels.isArray || channels.size() == 0) {
                return "없음\n"
            }

            val result = StringBuilder()
            channels.forEach { channel ->
                val name = if (channel.has("name")) {
                    "#" + channel.get("name").asText()
                } else if (channel.has("user")) {
                    val user = channel.get("user").asText()
                    "DM: $user"
                } else {
                    "?"
                }
                val id = channel.get("id").asText()
                result.append("  $name (ID: $id)\n")
            }

            result.toString()

        } catch (e: Exception) {
            "오류: ${e.message}\n"
        }
    }

    // ===========================================
    // 메시지 읽기
    // ===========================================

    @McpTool(description = """
        특정 채널의 최근 메시지를 읽습니다.

        파라미터:
        - channelId: 채널 ID (예: C0123456789)
        - limit: 조회할 메시지 수 (기본 50, 최대 200)

        참고: Bot에 channels:history 또는 groups:history, im:history scope가 필요합니다.
    """)
    @Tool(description = """
        특정 채널의 최근 메시지를 읽습니다.

        파라미터:
        - channelId: 채널 ID (예: C0123456789)
        - limit: 조회할 메시지 수 (기본 50, 최대 200)

        참고: Bot에 channels:history 또는 groups:history, im:history scope가 필요합니다.
    """)
    fun getMessages(
        @ToolParam(description = "채널 ID (listChannels로 확인 가능)") channelId: String,
        @ToolParam(description = "조회할 메시지 수 (1~200, 기본 50)") limit: Int = 50
    ): String {
        if (token.isBlank()) {
            return "오류: Slack Token이 설정되지 않았습니다."
        }

        val actualLimit = limit.coerceIn(1, 200)
        log.info("[Tool] getMessages 호출: channelId=$channelId, limit=$actualLimit")

        return try {
            val response = restClient.get()
                .uri("$baseUrl/conversations.history?channel=$channelId&limit=$actualLimit")
                .header("Authorization", "Bearer $token")
                .retrieve()
                .body(String::class.java)

            val json = objectMapper.readTree(response)

            if (!json.get("ok").asBoolean()) {
                val error = json.get("error").asText()
                return "메시지 조회 실패: $error\n\n채널 ID가 올바른지 확인하세요."
            }

            val messages = json.get("messages")
            if (!messages.isArray || messages.size() == 0) {
                return "메시지가 없습니다."
            }

            val result = StringBuilder("=== 채널 $channelId 메시지 (최근 ${messages.size()}개) ===\n\n")

            // Slack API는 최신 메시지가 먼저 옴
            messages.reversed().forEach { msg ->
                val ts = msg.get("ts").asText()
                val timestamp = Instant.ofEpochSecond(ts.split(".")[0].toLong())
                val user = msg.get("user")?.asText() ?: msg.get("bot_id")?.asText() ?: "?"
                val text = msg.get("text")?.asText() ?: "(첨부파일 또는 리액션)"

                result.append("[$timestamp] $user: $text\n\n")
            }

            result.toString()

        } catch (e: Exception) {
            log.error("getMessages 실패: ${e.message}", e)
            "오류: ${e.message}"
        }
    }

    @McpTool(description = """
        채널 이름으로 메시지를 읽습니다 (채널 ID를 모를 때 사용).

        파라미터:
        - channelName: 채널 이름 (예: coin-bot-alert, #coin-bot-alert)
        - limit: 조회할 메시지 수 (기본 50, 최대 200)
    """)
    @Tool(description = """
        채널 이름으로 메시지를 읽습니다 (채널 ID를 모를 때 사용).

        파라미터:
        - channelName: 채널 이름 (예: coin-bot-alert, #coin-bot-alert)
        - limit: 조회할 메시지 수 (기본 50, 최대 200)
    """)
    fun getMessagesByChannelName(
        @ToolParam(description = "채널 이름 (# 없이)") channelName: String,
        @ToolParam(description = "조회할 메시지 수 (1~200, 기본 50)") limit: Int = 50
    ): String {
        if (token.isBlank()) {
            return "오류: Slack Token이 설정되지 않았습니다."
        }

        log.info("[Tool] getMessagesByChannelName 호출: channelName=$channelName")

        // 1. 채널 ID 찾기
        val channelId = findChannelIdByName(channelName)
        if (channelId == null) {
            return "채널을 찾을 수 없습니다: $channelName\n\nlistChannels()로 채널 목록을 확인하세요."
        }

        // 2. 메시지 조회
        return getMessages(channelId, limit)
    }

    @McpTool(description = """
        특정 사용자가 보낸 DM을 조회합니다.

        파라미터:
        - userId: 사용자 ID (listAllConversations로 확인 가능)
        - limit: 조회할 메시지 수 (기본 50, 최대 200)
    """)
    @Tool(description = """
        특정 사용자가 보낸 DM을 조회합니다.

        파라미터:
        - userId: 사용자 ID (listAllConversations로 확인 가능)
        - limit: 조회할 메시지 수 (기본 50, 최대 200)
    """)
    fun getDmMessages(
        @ToolParam(description = "사용자 ID") userId: String,
        @ToolParam(description = "조회할 메시지 수 (1~200, 기본 50)") limit: Int = 50
    ): String {
        if (token.isBlank()) {
            return "오류: Slack Token이 설정되지 않았습니다."
        }

        log.info("[Tool] getDmMessages 호출: userId=$userId")

        return try {
            // 1. DM 채널 ID 찾기
            val dmChannelId = findDmChannelId(userId)
            if (dmChannelId == null) {
                return "DM 채널을 찾을 수 없습니다: $userId"
            }

            // 2. 메시지 조회
            val response = restClient.get()
                .uri("$baseUrl/conversations.history?channel=$dmChannelId&limit=${limit.coerceIn(1, 200)}")
                .header("Authorization", "Bearer $token")
                .retrieve()
                .body(String::class.java)

            val json = objectMapper.readTree(response)

            if (!json.get("ok").asBoolean()) {
                return "DM 조회 실패: ${json.get("error").asText()}"
            }

            val messages = json.get("messages")
            if (!messages.isArray || messages.size() == 0) {
                return "DM 메시지가 없습니다."
            }

            val result = StringBuilder("=== DM with $userId (최근 ${messages.size()}개) ===\n\n")

            messages.reversed().forEach { msg ->
                val ts = msg.get("ts").asText()
                val timestamp = Instant.ofEpochSecond(ts.split(".")[0].toLong())
                val text = msg.get("text")?.asText() ?: "(첨부파일)"

                result.append("[$timestamp] $text\n\n")
            }

            result.toString()

        } catch (e: Exception) {
            log.error("getDmMessages 실패: ${e.message}", e)
            "오류: ${e.message}"
        }
    }

    // ===========================================
    // 메시지 보내기
    // ===========================================

    @McpTool(description = """
        특정 채널에 메시지를 보냅니다.

        파라미터:
        - channelId: 메시지를 보낼 채널 ID
        - text: 메시지 내용

        참고: Bot에 chat:write scope가 필요합니다.
    """)
    @Tool(description = """
        특정 채널에 메시지를 보냅니다.

        파라미터:
        - channelId: 메시지를 보낼 채널 ID
        - text: 메시지 내용

        참고: Bot에 chat:write scope가 필요합니다.
    """)
    fun sendMessage(
        @ToolParam(description = "채널 ID") channelId: String,
        @ToolParam(description = "메시지 내용") text: String
    ): String {
        if (token.isBlank()) {
            return "오류: Slack Token이 설정되지 않았습니다."
        }

        log.info("[Tool] sendMessage 호출: channelId=$channelId, text=$text")

        return try {
            val response = restClient.post()
                .uri("$baseUrl/chat.postMessage")
                .header("Authorization", "Bearer $token")
                .body(mapOf(
                    "channel" to channelId,
                    "text" to text
                ))
                .retrieve()
                .body(String::class.java)

            val json = objectMapper.readTree(response)

            if (!json.get("ok").asBoolean()) {
                val error = json.get("error").asText()
                return "메시지 전송 실패: $error"
            }

            "메시지 전송 성공: $channelId"

        } catch (e: Exception) {
            log.error("sendMessage 실패: ${e.message}", e)
            "오류: ${e.message}"
        }
    }

    @McpTool(description = """
        채널 이름으로 메시지를 보냅니다 (채널 ID를 모를 때 사용).

        파라미터:
        - channelName: 채널 이름 (# 없이)
        - text: 메시지 내용
    """)
    @Tool(description = """
        채널 이름으로 메시지를 보냅니다 (채널 ID를 모를 때 사용).

        파라미터:
        - channelName: 채널 이름 (# 없이)
        - text: 메시지 내용
    """)
    fun sendMessageToChannel(
        @ToolParam(description = "채널 이름 (# 없이)") channelName: String,
        @ToolParam(description = "메시지 내용") text: String
    ): String {
        if (token.isBlank()) {
            return "오류: Slack Token이 설정되지 않았습니다."
        }

        log.info("[Tool] sendMessageToChannel 호출: channelName=$channelName")

        val channelId = findChannelIdByName(channelName)
        if (channelId == null) {
            return "채널을 찾을 수 없습니다: $channelName"
        }

        return sendMessage(channelId, text)
    }

    @McpTool(description = """
        특정 사용자에게 DM을 보냅니다.

        파라미터:
        - userId: 사용자 ID
        - text: 메시지 내용

        참고: 먼저 DM을 열려면 사용자와의 대화가 있어야 합니다.
    """)
    @Tool(description = """
        특정 사용자에게 DM을 보냅니다.

        파라미터:
        - userId: 사용자 ID
        - text: 메시지 내용

        참고: 먼저 DM을 열려면 사용자와의 대화가 있어야 합니다.
    """)
    fun sendDm(
        @ToolParam(description = "사용자 ID") userId: String,
        @ToolParam(description = "메시지 내용") text: String
    ): String {
        if (token.isBlank()) {
            return "오류: Slack Token이 설정되지 않았습니다."
        }

        log.info("[Tool] sendDm 호출: userId=$userId")

        return try {
            // 1. DM 채널 열기
            val openResponse = restClient.post()
                .uri("$baseUrl/conversations.open")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .body(mapOf("users" to userId))
                .retrieve()
                .body(String::class.java)

            val openJson = objectMapper.readTree(openResponse)

            val okNode = openJson.get("ok")
            if (okNode == null || !okNode.asBoolean()) {
                val errorNode = openJson.get("error")
                return "DM 열기 실패: ${errorNode?.asText() ?: "unknown"}"
            }

            val channelNode = openJson.get("channel")
            if (channelNode == null) {
                return "DM 열기 실패: channel not found"
            }
            val idNode = channelNode.get("id")
            if (idNode == null) {
                return "DM 열기 실패: channel id not found"
            }
            val channelId = idNode.asText()

            // 2. 메시지 전송
            sendMessage(channelId, text)

        } catch (e: Exception) {
            log.error("sendDm 실패: ${e.message}", e)
            "오류: ${e.message}"
        }
    }

    // ===========================================
    // 헬퍼 메서드
    // ===========================================

    private fun findChannelIdByName(channelName: String): String? {
        return try {
            // # 제거
            val cleanName = channelName.removePrefix("#")

            val response = restClient.get()
                .uri("$baseUrl/conversations.list?types=public_channel,private_channel")
                .header("Authorization", "Bearer $token")
                .retrieve()
                .body(String::class.java)

            val json = objectMapper.readTree(response)

            if (!json.get("ok").asBoolean()) {
                return null
            }

            val channels = json.get("channels")
            channels?.firstNotNullOfOrNull { channel ->
                if (channel.get("name").asText() == cleanName) {
                    channel.get("id").asText()
                } else null
            }

        } catch (e: Exception) {
            log.error("findChannelIdByName 실패: ${e.message}")
            null
        }
    }

    private fun findDmChannelId(userId: String): String? {
        return try {
            val response = restClient.get()
                .uri("$baseUrl/conversations.list?types=im")
                .header("Authorization", "Bearer $token")
                .retrieve()
                .body(String::class.java)

            val json = objectMapper.readTree(response)

            if (!json.get("ok").asBoolean()) {
                return null
            }

            val channels = json.get("channels")
            channels?.firstNotNullOfOrNull { channel ->
                if (channel.get("user").asText() == userId) {
                    channel.get("id").asText()
                } else null
            }

        } catch (e: Exception) {
            log.error("findDmChannelId 실패: ${e.message}")
            null
        }
    }
}
