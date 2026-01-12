---
name: spring-ai
description: Spring AI 1.1.x 프레임워크 사용 가이드. ChatClient API, Tool Calling, Advisors, MCP Server, Anthropic/OpenAI 설정 등. Spring AI 코드 작성 시 반드시 참조.
allowed-tools:
  - Read
  - Bash
  - Grep
  - Edit
  - Write
---

# Spring AI 1.1.x 완벽 가이드

## 개요

Spring AI 1.1.x (Spring Boot 3.5.x 호환)의 핵심 API와 올바른 사용 패턴을 정의한다.
이 문서는 Context7 MCP를 통해 Spring AI 공식 문서를 기반으로 작성되었다.

---

## 1. ChatClient API

### 1.1 ChatClient 생성

**자동 구성 빌더 주입 (권장):**
```kotlin
@RestController
class ChatController(chatClientBuilder: ChatClient.Builder) {
    private val chatClient = chatClientBuilder.build()

    @GetMapping("/chat")
    fun chat(@RequestParam message: String): String {
        return chatClient.prompt()
            .user(message)
            .call()
            .content()
    }
}
```

**프로그래매틱 생성:**
```kotlin
// 간단한 생성
val chatClient = ChatClient.create(chatModel)

// 빌더 사용 (기본값 설정)
val chatClient = ChatClient.builder(chatModel)
    .defaultSystem("You are a helpful assistant.")
    .defaultOptions(OpenAiChatOptions.builder()
        .model("gpt-4o")
        .temperature(0.7)
        .build())
    .build()
```

### 1.2 다중 ChatClient (다중 모델)

**ObjectProvider 방식 (권장 - 호환성 높음):**
```kotlin
@Configuration
class LlmConfig {
    @Bean("anthropicChatClient")
    fun anthropicChatClient(chatModelProvider: ObjectProvider<AnthropicChatModel>): ChatClient? {
        return chatModelProvider.ifAvailable?.let { ChatClient.create(it) }
    }

    @Bean("openAiChatClient")
    fun openAiChatClient(chatModelProvider: ObjectProvider<OpenAiChatModel>): ChatClient? {
        return chatModelProvider.ifAvailable?.let { ChatClient.create(it) }
    }
}
```

**@Qualifier로 선택:**
```kotlin
@Service
class MyService(
    @Qualifier("anthropicChatClient") private val anthropicClient: ChatClient?,
    @Qualifier("openAiChatClient") private val openAiClient: ChatClient?
)
```

### 1.3 응답 형식

**String 응답:**
```kotlin
val answer: String = chatClient.prompt()
    .user("질문")
    .call()
    .content()
```

**ChatResponse (메타데이터 포함):**
```kotlin
val response: ChatResponse = chatClient.prompt()
    .user("질문")
    .call()
    .chatResponse()

// 토큰 사용량 등 메타데이터 접근
val usage = response.metadata.usage
```

**Entity 매핑 (구조화된 출력):**
```kotlin
data class ActorFilms(val actor: String, val movies: List<String>)

val result: ActorFilms = chatClient.prompt()
    .user("배우 정보 생성")
    .call()
    .entity(ActorFilms::class.java)

// 제네릭 컬렉션
val actors: List<ActorFilms> = chatClient.prompt()
    .user("5명의 배우 정보 생성")
    .call()
    .entity(object : ParameterizedTypeReference<List<ActorFilms>>() {})
```

### 1.4 스트리밍

```kotlin
val flux: Flux<String> = chatClient.prompt()
    .user("긴 이야기 생성")
    .stream()
    .content()

val responses: Flux<ChatResponse> = chatClient.prompt()
    .user("긴 이야기 생성")
    .stream()
    .chatResponse()
```

### 1.5 프롬프트 템플릿

**기본 템플릿 (StringTemplate):**
```kotlin
val answer = chatClient.prompt()
    .user { u -> u
        .text("Tell me about {topic} in {language}")
        .param("topic", "Spring AI")
        .param("language", "Korean")
    }
    .call()
    .content()
```

**시스템 메시지:**
```kotlin
val chatClient = ChatClient.builder(chatModel)
    .defaultSystem("You are a {role} assistant")
    .build()

val response = chatClient.prompt()
    .system { sp -> sp.param("role", "helpful") }
    .user("질문")
    .call()
    .content()
```

**메시지에 메타데이터 추가:**
```kotlin
val response = chatClient.prompt()
    .user { u -> u
        .text("질문")
        .metadata("messageId", "msg-123")
        .metadata("userId", "user-456")
    }
    .call()
    .content()
```

### 1.6 기본값 설정

```kotlin
val chatClient = ChatClient.builder(chatModel)
    .defaultSystem("기본 시스템 메시지")
    .defaultOptions(chatOptions)           // 모델 옵션
    .defaultAdvisors(advisor1, advisor2)   // 어드바이저
    .defaultTools(MyTools())               // 도구
    .build()
```

---

## 2. Tool Calling (함수 호출)

### 2.1 @Tool 어노테이션 방식

```kotlin
@Component
class DateTimeTools {
    @Tool(description = "현재 날짜와 시간을 반환합니다")
    fun getCurrentDateTime(): String {
        return LocalDateTime.now()
            .atZone(LocaleContextHolder.getTimeZone().toZoneId())
            .toString()
    }

    @Tool(description = "알람을 설정합니다")
    fun setAlarm(
        @ToolParam(description = "ISO-8601 형식의 시간") time: String
    ) {
        println("Alarm set for $time")
    }
}
```

**@Tool 속성:**
| 속성 | 설명 | 기본값 |
|------|------|--------|
| `name` | 도구 이름 | 메서드명 |
| `description` | 도구 설명 (필수!) | - |
| `returnDirect` | AI 모델 우회하고 결과 직접 반환 | false |
| `resultConverter` | 결과 변환기 | DefaultToolCallResultConverter |

**@ToolParam 속성:**
| 속성 | 설명 | 기본값 |
|------|------|--------|
| `description` | 매개변수 설명 | - |
| `required` | 필수 여부 | true |

**returnDirect 사용 예시:**
```kotlin
@Tool(description = "도서관 운영 시간 조회", returnDirect = true)
fun getLibraryHours(): String {
    // AI 모델을 거치지 않고 결과를 직접 사용자에게 반환
    return "운영 시간: 월-금 9am-8pm, 토-일 10am-6pm"
}
```

### 2.2 ChatClient에 도구 등록

**런타임 등록:**
```kotlin
val response = chatClient.prompt()
    .user("내일 알람 설정해줘")
    .tools(DateTimeTools())  // 이 요청에만 적용
    .call()
    .content()
```

**기본 도구 등록:**
```kotlin
val chatClient = ChatClient.builder(chatModel)
    .defaultTools(DateTimeTools())
    .build()
```

**ToolCallbacks 유틸리티:**
```kotlin
// @Tool 메서드들을 ToolCallback으로 변환
val callbacks: Array<ToolCallback> = ToolCallbacks.from(DateTimeTools())

// ChatModel에서 직접 사용
val options = ToolCallingChatOptions.builder()
    .toolCallbacks(callbacks)
    .build()
```

### 2.3 Function Bean 방식

```kotlin
@Configuration
class ToolConfig {
    @Bean
    @Description("날씨 정보를 가져옵니다")
    fun currentWeather(): Function<WeatherRequest, WeatherResponse> {
        return WeatherService()
    }
}

// 사용
chatClient.prompt()
    .toolNames("currentWeather")  // 빈 이름으로 참조
    .user("서울 날씨는?")
    .call()
    .content()
```

### 2.4 Tool Context (추가 컨텍스트)

**ToolContext는 AI 모델에 전송되지 않음 - 민감한 정보 전달에 안전**

```kotlin
@Tool(description = "고객 정보 조회")
fun getCustomer(id: Long, toolContext: ToolContext): Customer {
    // toolContext는 AI 모델에 전송되지 않음
    val tenantId = toolContext.context["tenantId"] as String
    return customerRepository.findById(id, tenantId)
}

// ChatClient에서 호출 시
chatClient.prompt()
    .tools(CustomerTools())
    .toolContext(mapOf("tenantId" to "acme"))
    .user("고객 42번 정보 알려줘")
    .call()
    .content()

// ChatModel에서 직접 사용 시
val options = ToolCallingChatOptions.builder()
    .toolCallbacks(ToolCallbacks.from(CustomerTools()))
    .toolContext(mapOf("tenantId" to "acme"))
    .build()
```

---

## 3. Structured Output (구조화된 출력)

### 3.1 BeanOutputConverter

**JSON Schema 자동 생성:**
```kotlin
// @JsonProperty(required = true) 사용 권장
data class MathReasoning(
    @JsonProperty(required = true, value = "steps")
    val steps: List<Step>,
    @JsonProperty(required = true, value = "final_answer")
    val finalAnswer: String
) {
    data class Step(
        @JsonProperty(required = true, value = "explanation")
        val explanation: String,
        @JsonProperty(required = true, value = "output")
        val output: String
    )
}

val outputConverter = BeanOutputConverter(MathReasoning::class.java)
val jsonSchema = outputConverter.jsonSchema

val prompt = Prompt(
    "8x + 7 = -23 풀어줘",
    OpenAiChatOptions.builder()
        .model("gpt-4o-mini")
        .responseFormat(ResponseFormat(ResponseFormat.Type.JSON_SCHEMA, jsonSchema))
        .build()
)

val response = chatModel.call(prompt)
val result: MathReasoning = outputConverter.convert(response.result.output.content)
```

### 3.2 ChatClient entity() 메서드

```kotlin
// 단일 객체
val result: MathReasoning = chatClient.prompt()
    .user("문제 풀이")
    .call()
    .entity(MathReasoning::class.java)

// 컬렉션
val results: List<MathReasoning> = chatClient.prompt()
    .user("여러 문제 풀이")
    .call()
    .entity(object : ParameterizedTypeReference<List<MathReasoning>>() {})
```

---

## 4. Advisors API

### 4.1 개념

Advisor는 AI 상호작용을 **가로채고, 수정하고, 향상**시키는 패턴.
- 반복되는 Generative AI 패턴 캡슐화
- LLM 입출력 데이터 변환
- Chain of Responsibility 패턴

### 4.2 기본 제공 Advisors

| Advisor | 기능 |
|---------|------|
| `MessageChatMemoryAdvisor` | 대화 이력을 메시지로 추가 |
| `PromptChatMemoryAdvisor` | 대화 이력을 시스템 텍스트에 통합 |
| `VectorStoreChatMemoryAdvisor` | VectorStore에서 검색 |
| `QuestionAnswerAdvisor` | RAG 기본 구현 (Naive RAG) |
| `RetrievalAugmentationAdvisor` | RAG 모듈러 구현 (Modular RAG) |
| `SimpleLoggerAdvisor` | 요청/응답 로깅 |

### 4.3 Advisor 등록

```kotlin
val chatMemory = MessageWindowChatMemory.builder()
    .maxMessages(20)
    .build()

val chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(
        MessageChatMemoryAdvisor.builder(chatMemory).build(),
        SimpleLoggerAdvisor()
    )
    .build()
```

### 4.4 커스텀 Advisor

```kotlin
class MyAdvisor : CallAdvisor, StreamAdvisor {
    override fun getName() = "MyAdvisor"
    override fun getOrder() = 0  // 낮을수록 먼저 실행

    override fun adviseCall(
        request: ChatClientRequest,
        chain: CallAdvisorChain
    ): ChatClientResponse {
        // Before: 요청 수정
        logRequest(request)

        // 다음 advisor/모델 호출
        val response = chain.nextCall(request)

        // After: 응답 수정
        logResponse(response)
        return response
    }

    override fun adviseStream(
        request: ChatClientRequest,
        chain: StreamAdvisorChain
    ): Flux<ChatClientResponse> {
        logRequest(request)

        val responses = chain.nextStream(request)

        // 스트리밍 응답 집계 후 로깅
        return ChatClientMessageAggregator()
            .aggregateChatClientResponse(responses) { logResponse(it) }
    }
}
```

---

## 5. Chat Memory (대화 기록)

### 5.1 MessageWindowChatMemory

```kotlin
// 메모리 생성 (최근 N개 메시지 유지)
val chatMemory = MessageWindowChatMemory.builder()
    .maxMessages(10)
    .build()

// ChatClient에 적용
val chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
    .build()
```

### 5.2 Conversation ID로 대화 관리

```kotlin
val conversationId = "user-session-123"

val response = chatClient.prompt()
    .user("내 이름은 Alice야")
    .advisors { a -> a.param(ChatMemory.CONVERSATION_ID, conversationId) }
    .call()
    .content()

// 같은 conversationId로 이어서 대화
val followUp = chatClient.prompt()
    .user("내 이름이 뭐였지?")
    .advisors { a -> a.param(ChatMemory.CONVERSATION_ID, conversationId) }
    .call()
    .content()  // "Alice입니다"
```

### 5.3 ChatMemoryRepository 구현체

| Repository | 설명 |
|------------|------|
| `InMemoryChatMemoryRepository` | 메모리 저장 (기본) |
| `JdbcChatMemoryRepository` | JDBC 저장 |
| `CassandraChatMemoryRepository` | Cassandra 저장 |
| `Neo4jChatMemoryRepository` | Neo4j 저장 |

```kotlin
// JDBC Repository 사용 예시
@Bean
fun chatMemory(jdbcRepository: JdbcChatMemoryRepository): ChatMemory {
    return MessageWindowChatMemory.builder()
        .chatMemoryRepository(jdbcRepository)
        .maxMessages(20)
        .build()
}
```

---

## 6. RAG (Retrieval Augmented Generation)

### 6.1 QuestionAnswerAdvisor (Naive RAG)

```kotlin
// 기본 사용
val response = ChatClient.builder(chatModel)
    .build()
    .prompt()
    .advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
    .user("Spring AI란?")
    .call()
    .chatResponse()

// SearchRequest 커스텀
val qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
    .searchRequest(SearchRequest.builder()
        .topK(4)
        .similarityThreshold(0.75)
        .build())
    .build()

val chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(qaAdvisor)
    .build()
```

### 6.2 RetrievalAugmentationAdvisor (Modular RAG)

```kotlin
val ragAdvisor = RetrievalAugmentationAdvisor.builder()
    .documentRetriever(VectorStoreDocumentRetriever.builder()
        .vectorStore(vectorStore)
        .similarityThreshold(0.50)
        .build())
    .build()

val answer = chatClient.prompt()
    .advisors(ragAdvisor)
    .user("질문")
    .call()
    .content()
```

### 6.3 Conversational RAG (대화형 RAG)

```kotlin
@Service
class ConversationalRAGService(chatModel: ChatModel, vectorStore: VectorStore) {
    private val chatClient: ChatClient

    init {
        val memory = MessageWindowChatMemory.builder()
            .maxMessages(10)
            .build()

        // 질의 압축기 (후속 질문을 독립적인 질문으로 변환)
        val queryCompressor = CompressionQueryTransformer.builder()
            .chatClientBuilder(ChatClient.builder(chatModel))
            .build()

        val memoryAdvisor = MessageChatMemoryAdvisor.builder(memory).build()

        val ragAdvisor = RetrievalAugmentationAdvisor.builder()
            .queryTransformers(queryCompressor)
            .documentRetriever(VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .build())
            .build()

        chatClient = ChatClient.builder(chatModel)
            .defaultAdvisors(memoryAdvisor, ragAdvisor)
            .build()
    }

    fun chat(conversationId: String, message: String): String {
        return chatClient.prompt()
            .user(message)
            .advisors { a -> a.param(ChatMemory.CONVERSATION_ID, conversationId) }
            .call()
            .content()
    }
}
```

---

## 7. Multimodal (멀티모달)

### 7.1 이미지 입력

```kotlin
// ClassPath 리소스에서 이미지 로드
val imageResource = ClassPathResource("/images/test.png")

// UserMessage 생성 (텍스트 + 이미지)
val userMessage = UserMessage.builder()
    .text("이 사진에서 무엇이 보이나요?")
    .media(Media(MimeTypeUtils.IMAGE_PNG, imageResource))
    .build()

// ChatModel 호출
val response = chatModel.call(Prompt(userMessage))
```

### 7.2 모델별 멀티모달 설정

**OpenAI (GPT-4o):**
```kotlin
val userMessage = UserMessage(
    "이 이미지를 설명해줘",
    Media(MimeTypeUtils.IMAGE_PNG, imageResource)
)

val response = chatModel.call(Prompt(
    userMessage,
    OpenAiChatOptions.builder()
        .model("gpt-4o")
        .build()
))
```

**Anthropic (Claude):**
```kotlin
val userMessage = UserMessage(
    "이 이미지를 분석해줘",
    listOf(Media(MimeTypeUtils.IMAGE_PNG, imageResource))
)

val response = chatModel.call(Prompt(userMessage))
```

### 7.3 URL 기반 이미지

```kotlin
val imageUrl = URI.create("https://example.com/image.png").toURL()

val userMessage = UserMessage(
    "이 이미지에 무엇이 있나요?",
    Media(MimeTypeUtils.IMAGE_PNG, imageUrl)
)
```

---

## 8. MCP Server

### 8.1 의존성

**SSE (WebMVC):**
```gradle
implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
```

**SSE (WebFlux):**
```gradle
implementation("org.springframework.ai:spring-ai-starter-mcp-server-webflux")
```

### 8.2 설정

```yaml
spring:
  ai:
    mcp:
      server:
        name: my-mcp-server
        version: 1.0.0
        type: SYNC               # SYNC 또는 ASYNC
        protocol: SSE            # SSE, STREAMABLE, STDIO
        capabilities:
          tool: true
          resource: true
          prompt: true
          completion: true
        resource-change-notification: true
        prompt-change-notification: true
        tool-change-notification: true
        request-timeout: 20s
```

### 8.3 @McpTool

```kotlin
@Component
class CalculatorTools {
    @McpTool(name = "add", description = "두 숫자를 더합니다")
    fun add(
        @McpToolParam(description = "첫 번째 숫자", required = true) a: Int,
        @McpToolParam(description = "두 번째 숫자", required = true) b: Int
    ): Int = a + b

    @McpTool(name = "multiply", description = "두 숫자를 곱합니다")
    fun multiply(
        @McpToolParam(description = "첫 번째 숫자") a: Int,
        @McpToolParam(description = "두 번째 숫자") b: Int
    ): Int = a * b
}
```

### 8.4 @McpResource

```kotlin
@Component
class ConfigManager {
    @McpResource(uri = "config://{key}", name = "Configuration")
    fun getConfig(key: String): String {
        return configData[key] ?: "not found"
    }
}
```

### 8.5 @McpPrompt

```kotlin
@Component
class PromptProvider {
    @McpPrompt(name = "weather_summary", description = "날씨 요약 프롬프트")
    fun getWeatherSummary(
        @McpPromptArg(name = "location") location: String
    ): List<PromptMessage> {
        return listOf(
            PromptMessage(
                Role.USER,
                TextContent("$location 의 날씨를 요약해주세요")
            )
        )
    }
}
```

---

## 9. 모델별 설정

### 9.1 Anthropic (Claude)

**의존성:**
```gradle
implementation("org.springframework.ai:spring-ai-anthropic-spring-boot-starter")
```

**application.yml:**
```yaml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-sonnet-4-5
          temperature: 0.7
          max-tokens: 4096
```

**런타임 옵션:**
```kotlin
val response = chatModel.call(
    Prompt(
        "질문",
        AnthropicChatOptions.builder()
            .model("claude-sonnet-4-5")
            .temperature(0.4)
            .maxTokens(500)
            .build()
    )
)
```

**Thinking (Extended Reasoning):**

> **Claude 4 모델**: thinking 기본 활성화 (명시적 설정 불필요)
> **Claude 3.7 Sonnet**: 명시적 설정 필요

```kotlin
// Claude 3.7 Sonnet - 명시적 설정 필요
val response = chatClient.prompt()
    .options(AnthropicChatOptions.builder()
        .model("claude-3-7-sonnet-latest")
        .temperature(1.0)  // thinking 사용 시 temperature=1.0 필수
        .maxTokens(8192)
        .thinking(AnthropicApi.ThinkingType.ENABLED, 2048)  // budgetTokens >= 1024
        .build())
    .user("복잡한 수학 문제")
    .call()
    .chatResponse()

// Claude 4 모델 - thinking 기본 활성화
val response4 = chatClient.prompt()
    .options(AnthropicChatOptions.builder()
        .model("claude-opus-4-0")
        .maxTokens(8192)
        // thinking 설정 불필요 (기본 활성화)
        .build())
    .user("복잡한 문제")
    .call()
    .chatResponse()

// Thinking 결과 처리
response.results.forEach { generation ->
    val message = generation.output
    if (message.text != null) {
        println("응답: ${message.text}")
    }
    if (message.metadata.containsKey("thinking")) {
        println("사고 과정: ${message.metadata["thinking"]}")
    }
}
```

---

### 9.2 OpenAI

**의존성:**
```gradle
implementation("org.springframework.ai:spring-ai-openai-spring-boot-starter")
```

**application.yml:**
```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o-mini
          temperature: 0.8
          max-tokens: 2048
```

**런타임 옵션:**
```kotlin
val response = chatModel.call(
    Prompt(
        "질문",
        OpenAiChatOptions.builder()
            .model("gpt-4o")
            .temperature(0.4)
            .maxTokens(200)
            .build()
    )
)
```

**추론 모델 (o1, o3, o4-mini 시리즈):**

> **중요**: `maxTokens`와 `maxCompletionTokens`는 **상호 배타적**
> - 일반 모델 (gpt-4o, gpt-4o-mini): `maxTokens` 사용
> - 추론 모델 (o1, o3, o4-mini): `maxCompletionTokens` 사용

```kotlin
// 추론 모델은 maxCompletionTokens 사용!
val response = chatModel.call(
    Prompt(
        "복잡한 문제를 단계별로 풀어줘",
        OpenAiChatOptions.builder()
            .model("o1-preview")
            .maxCompletionTokens(1000)  // maxTokens 아님!
            .build()
    )
)
```

---

### 9.3 Google Vertex AI (Gemini)

**의존성:**
```gradle
implementation("org.springframework.ai:spring-ai-vertex-ai-gemini-spring-boot-starter")
```

**application.yml:**
```yaml
spring:
  ai:
    vertex:
      ai:
        gemini:
          project-id: ${GCP_PROJECT_ID}
          location: ${GCP_LOCATION}  # us-central1, asia-northeast3 등
          chat:
            options:
              model: gemini-2.0-flash
              temperature: 0.5
```

**런타임 옵션:**
```kotlin
val response = chatModel.call(
    Prompt(
        "질문",
        VertexAiGeminiChatOptions.builder()
            .model(ChatModel.GEMINI_2_0_FLASH)
            .temperature(0.4)
            .build()
    )
)
```

**수동 설정:**
```kotlin
val vertexApi = VertexAI(projectId, location)

val chatModel = VertexAiGeminiChatModel(
    vertexApi,
    VertexAiGeminiChatOptions.builder()
        .model(ChatModel.GEMINI_2_0_FLASH)
        .temperature(0.4)
        .build()
)
```

**주요 모델:**
| 모델 | 설명 |
|------|------|
| `gemini-2.0-flash` | 최신 빠른 모델 |
| `gemini-1.5-pro` | 고성능 모델 |
| `gemini-1.5-flash` | 빠른 응답 |

---

### 9.4 Azure OpenAI

**의존성:**
```gradle
implementation("org.springframework.ai:spring-ai-azure-openai-spring-boot-starter")
```

**application.yml:**
```yaml
spring:
  ai:
    azure:
      openai:
        api-key: ${AZURE_OPENAI_API_KEY}
        endpoint: ${AZURE_OPENAI_ENDPOINT}
        chat:
          options:
            deployment-name: my-gpt4-deployment  # Azure Portal에서 생성한 배포 이름
            temperature: 0.7
```

> **중요**: Azure OpenAI는 `model`이 아닌 `deployment-name` 사용

**런타임 옵션:**
```kotlin
val response = chatModel.call(
    Prompt(
        "질문",
        AzureOpenAiChatOptions.builder()
            .deploymentName("my-gpt4-deployment")
            .temperature(0.4)
            .maxTokens(200)
            .build()
    )
)
```

**수동 설정:**
```kotlin
val openAIClientBuilder = OpenAIClientBuilder()
    .credential(AzureKeyCredential(System.getenv("AZURE_OPENAI_API_KEY")))
    .endpoint(System.getenv("AZURE_OPENAI_ENDPOINT"))

val chatOptions = AzureOpenAiChatOptions.builder()
    .deploymentName("gpt-4o")
    .temperature(0.4)
    .maxTokens(200)
    .build()

val chatModel = AzureOpenAiChatModel.builder()
    .openAIClientBuilder(openAIClientBuilder)
    .defaultOptions(chatOptions)
    .build()
```

---

### 9.5 Ollama (로컬 LLM)

> Qwen3, DeepSeek, Llama, Mistral 등 다양한 오픈소스 모델을 로컬에서 실행

**의존성:**
```gradle
implementation("org.springframework.ai:spring-ai-ollama-spring-boot-starter")
```

**application.yml:**
```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: qwen3
          temperature: 0.7
```

**런타임 옵션:**
```kotlin
val response = chatModel.call(
    Prompt(
        "질문",
        OllamaChatOptions.builder()
            .model(OllamaModel.LLAMA3_2)
            .temperature(0.9)
            .build()
    )
)
```

**Thinking Mode (Qwen3, DeepSeek R1):**

> Qwen3, DeepSeek R1 등 추론 모델은 thinking mode 지원

```kotlin
val response = chatModel.call(
    Prompt(
        "'strawberry'에 'r'이 몇 개 있어?",
        OllamaChatOptions.builder()
            .model("qwen3")
            .enableThinking()  // thinking mode 활성화
            .build()
    )
)

// 추론 과정 접근
val thinking = response.result.metadata["thinking"]
val answer = response.result.output.content

println("추론 과정: $thinking")
println("최종 답변: $answer")
```

**OpenAI 호환 API로 Ollama 사용:**

> Ollama는 OpenAI 호환 API 제공 - OpenAiChatModel로도 접근 가능

```kotlin
@Configuration
class OllamaConfig {
    @Bean
    fun ollamaChatModel(): OpenAiChatModel {
        val openAiApi = OpenAiApi("http://localhost:11434", "ollama")
        return OpenAiChatModel(
            openAiApi,
            OpenAiChatOptions.builder()
                .model("deepseek-r1")  // 또는 qwen3, llama3.2 등
                .build()
        )
    }
}
```

**주요 모델:**
| 모델 | 설명 |
|------|------|
| `qwen3` | Alibaba Qwen3 (추론 모델) |
| `deepseek-r1` | DeepSeek R1 (추론 모델) |
| `llama3.2` | Meta Llama 3.2 |
| `mistral` | Mistral AI |
| `gemma2` | Google Gemma 2 |
| `phi3` | Microsoft Phi-3 |

---

### 9.6 AWS Bedrock

> Claude, Llama, Titan, Mistral 등 다양한 모델을 AWS에서 통합 제공

**의존성:**
```gradle
implementation("org.springframework.ai:spring-ai-bedrock-converse-spring-boot-starter")
```

**application.yml:**
```yaml
spring:
  ai:
    bedrock:
      aws:
        region: us-east-1
        access-key: ${AWS_ACCESS_KEY}
        secret-key: ${AWS_SECRET_KEY}
      converse:
        chat:
          options:
            model: anthropic.claude-3-5-sonnet-20240620-v1:0
            temperature: 0.7
            max-tokens: 500
```

**런타임 옵션:**
```kotlin
val options = BedrockChatOptions.builder()
    .model("anthropic.claude-3-5-sonnet-20240620-v1:0")
    .temperature(0.6)
    .maxTokens(300)
    .build()

val response = ChatClient.create(chatModel)
    .prompt("질문")
    .options(options)
    .call()
    .content()
```

**Tool Calling:**
```kotlin
val options = BedrockChatOptions.builder()
    .model("anthropic.claude-3-5-sonnet-20240620-v1:0")
    .temperature(0.6)
    .maxTokens(300)
    .toolCallbacks(listOf(
        FunctionToolCallback.builder("getCurrentWeather", WeatherService())
            .description("날씨 정보 조회")
            .inputType(WeatherService.Request::class.java)
            .build()
    ))
    .build()
```

**지원 모델:**
| 프로바이더 | 모델 ID 예시 |
|-----------|-------------|
| Anthropic Claude | `anthropic.claude-3-5-sonnet-20240620-v1:0` |
| Meta Llama | `meta.llama3-70b-instruct-v1:0` |
| Amazon Titan | `amazon.titan-text-express-v1` |
| Amazon Nova | `amazon.nova-pro-v1:0` |
| Mistral AI | `mistral.mistral-large-2407-v1:0` |
| Cohere | `cohere.command-r-plus-v1:0` |

---

### 9.7 DeepSeek (전용 API)

**의존성:**
```gradle
implementation("org.springframework.ai:spring-ai-deepseek-spring-boot-starter")
```

**application.yml:**
```yaml
spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      chat:
        options:
          model: deepseek-chat
          temperature: 0.7
```

**DeepSeek Reasoner (추론 모델):**

> Chain of Thought (CoT) 컨텐츠 접근 가능

```kotlin
val promptOptions = DeepSeekChatOptions.builder()
    .model(DeepSeekApi.ChatModel.DEEPSEEK_REASONER.getValue())
    .build()

val response = chatModel.call(Prompt("9.11과 9.8 중 어느 것이 더 큰가?", promptOptions))

// CoT 내용 접근 (deepseek-reasoner 모델에서만 가능)
val deepSeekMessage = response.result.output as DeepSeekAssistantMessage
val reasoningContent = deepSeekMessage.reasoningContent  // 추론 과정
val finalAnswer = deepSeekMessage.text                    // 최종 답변
```

**OpenAI 호환 API로 DeepSeek 사용:**
```yaml
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-reasoner
```

```kotlin
// Reasoning 내용 접근
val response = chatModel.call(Prompt("어느 숫자가 더 큰가: 9.11 vs 9.8?"))
val message = response.result.output
val reasoning = message.metadata["reasoningContent"]  // 추론 과정
val answer = message.content                          // 최종 답변
```

**주요 모델:**
| 모델 | 설명 |
|------|------|
| `deepseek-chat` | 일반 대화 모델 |
| `deepseek-reasoner` | 추론 모델 (CoT 지원) |

---

### 9.8 Mistral AI

**의존성:**
```gradle
implementation("org.springframework.ai:spring-ai-mistral-ai-spring-boot-starter")
```

**application.yml:**
```yaml
spring:
  ai:
    mistralai:
      api-key: ${MISTRAL_AI_API_KEY}
      chat:
        options:
          model: mistral-large-latest
          temperature: 0.7
```

**런타임 옵션:**
```kotlin
val response = chatModel.call(
    Prompt(
        "질문",
        MistralAiChatOptions.builder()
            .model(MistralAiApi.ChatModel.LARGE.getValue())
            .temperature(0.5)
            .maxTokens(200)
            .build()
    )
)
```

**수동 설정:**
```kotlin
val mistralAiApi = MistralAiApi(System.getenv("MISTRAL_AI_API_KEY"))

val chatModel = MistralAiChatModel(
    mistralAiApi,
    MistralAiChatOptions.builder()
        .model(MistralAiApi.ChatModel.LARGE.getValue())
        .temperature(0.4)
        .maxTokens(200)
        .build()
)
```

**주요 모델:**
| 모델 | 설명 |
|------|------|
| `mistral-large-latest` | 최신 대형 모델 |
| `mistral-small-latest` | 빠른 소형 모델 |
| `pixtral-large-latest` | 비전 모델 (멀티모달) |
| `codestral-latest` | 코드 생성 특화 |

---

### 9.9 OpenAI 호환 API (Groq, vLLM, Together AI 등)

> OpenAI API 형식을 따르는 서비스들은 base-url만 변경하여 사용 가능

#### Groq (빠른 추론)

```yaml
spring:
  ai:
    openai:
      api-key: ${GROQ_API_KEY}
      base-url: https://api.groq.com/openai
      chat:
        options:
          model: llama3-70b-8192
          temperature: 0.7
```

#### vLLM (로컬 추론 서버)

```bash
# vLLM 서버 실행
vllm serve deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B \
    --enable-reasoning \
    --reasoning-parser deepseek_r1
```

```yaml
spring:
  ai:
    openai:
      base-url: http://localhost:8000/v1
      chat:
        options:
          model: deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B
```

#### 다중 프로바이더 동시 사용

```kotlin
@Service
class MultiModelService(
    private val baseChatModel: OpenAiChatModel,
    private val baseOpenAiApi: OpenAiApi
) {
    fun multiClientFlow() {
        // Groq API로 변경
        val groqApi = baseOpenAiApi.mutate()
            .baseUrl("https://api.groq.com/openai")
            .apiKey(System.getenv("GROQ_API_KEY"))
            .build()

        val groqModel = baseChatModel.mutate()
            .openAiApi(groqApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model("llama3-70b-8192")
                .temperature(0.5)
                .build())
            .build()

        // OpenAI GPT-4로 변경
        val gpt4Api = baseOpenAiApi.mutate()
            .baseUrl("https://api.openai.com")
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .build()

        val gpt4Model = baseChatModel.mutate()
            .openAiApi(gpt4Api)
            .defaultOptions(OpenAiChatOptions.builder()
                .model("gpt-4o")
                .temperature(0.7)
                .build())
            .build()

        // 각 모델로 요청
        val groqResponse = ChatClient.builder(groqModel).build()
            .prompt("프랑스의 수도는?")
            .call()
            .content()

        val gpt4Response = ChatClient.builder(gpt4Model).build()
            .prompt("프랑스의 수도는?")
            .call()
            .content()
    }
}
```

---

### 9.10 중국 LLM (ZhiPu AI, MiniMax)

#### ZhiPu AI (GLM)

**의존성:**
```gradle
implementation("org.springframework.ai:spring-ai-zhipuai-spring-boot-starter")
```

**application.yml:**
```yaml
spring:
  ai:
    zhipuai:
      api-key: ${ZHIPUAI_API_KEY}
      chat:
        options:
          model: glm-4-air
          temperature: 0.7
```

**주요 모델:**
| 모델 | 설명 |
|------|------|
| `glm-4` | 최신 대형 모델 |
| `glm-4-air` | 빠른 경량 모델 |
| `glm-4v` | 비전 모델 |

#### MiniMax

**의존성:**
```gradle
implementation("org.springframework.ai:spring-ai-minimax-spring-boot-starter")
```

**application.yml:**
```yaml
spring:
  ai:
    minimax:
      api-key: ${MINIMAX_API_KEY}
      chat:
        options:
          model: abab6.5g-chat
          temperature: 0.7
```

---

### 9.11 프로바이더 비교표

| 프로바이더 | 주요 모델 | 특징 | Tool Calling | Thinking |
|-----------|----------|------|--------------|----------|
| **Anthropic** | Claude 4, 3.7 | 긴 컨텍스트, 안전성 | O | O (Claude 4 기본) |
| **OpenAI** | GPT-4o, o1/o3 | 범용, 추론 모델 | O | O (o1/o3) |
| **Google** | Gemini 2.0 | 멀티모달, 긴 컨텍스트 | O | X |
| **Azure OpenAI** | GPT-4o | 기업용, 규정 준수 | O | O |
| **Ollama** | Qwen3, DeepSeek R1 | 로컬 실행, 무료 | O | O |
| **AWS Bedrock** | Claude, Llama, Titan | 다중 모델, AWS 통합 | O | X |
| **DeepSeek** | deepseek-reasoner | 저렴한 비용, CoT | O | O |
| **Mistral** | mistral-large | 유럽 기반, 코드 특화 | O | X |
| **Groq** | Llama3-70b | 초고속 추론 | O | X |
| **ZhiPu AI** | GLM-4 | 중국어 최적화 | O | X |

---

## 10. 주의사항 및 Best Practices

### 10.1 ChatClient 생성

- **ObjectProvider 사용**: `@ConditionalOnBean` 대신 `ObjectProvider<T>.ifAvailable` 사용
- **다중 모델**: `@Qualifier`로 명시적 선택
- **Null Safety**: ChatClient가 null일 수 있음을 고려

### 10.2 Tool Calling

- **description 필수**: 모델이 도구 사용 여부를 판단하는 핵심 정보
- **@ToolParam description**: 매개변수 설명도 중요
- **반환 타입**: 직렬화 가능한 타입 사용 (data class, record 권장)
- **ToolContext**: 민감한 정보는 ToolContext로 전달 (AI 모델에 전송 안됨)
- **returnDirect**: 단순 조회성 도구에 사용하면 AI 모델 재호출 방지

### 10.3 Structured Output

- **@JsonProperty(required = true)** 사용 권장 (JSON Schema 정확성)
- **BeanOutputConverter**: 복잡한 구조에서 유용
- **entity() 메서드**: 간단한 매핑에 편리

### 10.4 Chat Memory

- **CONVERSATION_ID**: 대화별로 고유한 ID 사용
- **maxMessages**: 컨텍스트 윈도우 크기 고려해서 설정
- **Repository 선택**: 영속성 필요 시 JDBC/Cassandra/Neo4j

### 10.5 RAG

- **similarityThreshold**: 0.5~0.8 권장 (너무 낮으면 노이즈, 너무 높으면 누락)
- **topK**: 일반적으로 3~5개
- **CompressionQueryTransformer**: 대화형 RAG에서 후속 질문 처리에 유용

### 10.6 MCP Server

- **프로토콜 선택**:
  - STDIO: 단일 클라이언트, 서브프로세스로 실행
  - SSE: 다중 클라이언트, 웹 서버
  - Streamable-HTTP: 마이크로서비스, 클라우드
- **동기/비동기**: 대부분 SYNC로 충분, 고성능 필요시 ASYNC

### 10.7 모델 옵션

- **temperature**: 0 (결정론적) ~ 2 (창의적)
- **maxTokens vs maxCompletionTokens**:
  - 일반 모델: `maxTokens`
  - 추론 모델 (o1, o3, o4-mini): `maxCompletionTokens`
- **Anthropic Thinking**: Claude 3.7은 `temperature = 1.0` 필수, Claude 4는 기본 활성화

---

## 11. 버전 호환성

| Spring Boot | Spring AI | 비고 |
|-------------|-----------|------|
| 3.5.x | 1.1.x | 안정 버전 |
| 4.0.x | 2.0.x | 프리뷰 (호환성 문제 있음) |

**현재 프로젝트 설정:**
- Spring Boot 3.5.9
- Spring AI 1.1.2

---

## 참고 문서

### 핵심 API
- [Spring AI Reference](https://docs.spring.io/spring-ai/reference/)
- [ChatClient API](https://docs.spring.io/spring-ai/reference/api/chatclient.html)
- [Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Advisors](https://docs.spring.io/spring-ai/reference/api/advisors.html)
- [Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)
- [RAG](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)
- [Structured Output](https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html)
- [MCP Server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)

### 프로바이더별 문서
- [Anthropic Claude](https://docs.spring.io/spring-ai/reference/api/chat/anthropic-chat.html)
- [OpenAI](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)
- [Google Vertex AI Gemini](https://docs.spring.io/spring-ai/reference/api/chat/vertexai-gemini-chat.html)
- [Azure OpenAI](https://docs.spring.io/spring-ai/reference/api/chat/azure-openai-chat.html)
- [Ollama](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html)
- [AWS Bedrock](https://docs.spring.io/spring-ai/reference/api/chat/bedrock-converse.html)
- [DeepSeek](https://docs.spring.io/spring-ai/reference/api/chat/deepseek-chat.html)
- [Mistral AI](https://docs.spring.io/spring-ai/reference/api/chat/mistralai-chat.html)
- [Groq](https://docs.spring.io/spring-ai/reference/api/chat/groq-chat.html)
- [ZhiPu AI](https://docs.spring.io/spring-ai/reference/api/chat/zhipuai-chat.html)
- [MiniMax](https://docs.spring.io/spring-ai/reference/api/chat/minimax-chat.html)
