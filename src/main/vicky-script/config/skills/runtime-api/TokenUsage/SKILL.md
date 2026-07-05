---
name: TokenUsage
description: Kotlin class: org.example.vicky.io.OutboundMessage$TokenUsage.
group: runtime-api
---

# TokenUsage

Kotlin class. Create instances with `new TokenUsage(...)` or `TokenUsage(...)`.

Full class: `org.example.vicky.io.OutboundMessage$TokenUsage`

## Fields
- `conversationId`: String
- `userId`: String
- `groupId`: String
- `promptTokens`: int
- `completionTokens`: int
- `sessionTotalUsed`: int
- `content`: String
- `type`: String

## Constructors
- `TokenUsage(arg0: String, arg1: String, arg2: String, arg3: int, arg4: int, arg5: int, arg6: String, arg7: String)`
- `TokenUsage(arg0: String, arg1: String, arg2: String, arg3: int, arg4: int, arg5: int, arg6: String, arg7: String, arg8: int, arg9: DefaultConstructorMarker)`

## Methods
- `equals(arg0: Object): boolean`
- `toString(): String`
- `hashCode(): int`
- `getType(): String`
- `copy(arg0: String, arg1: String, arg2: String, arg3: int, arg4: int, arg5: int, arg6: String, arg7: String): TokenUsage`
- `getContent(): String`
- `getUserId(): String`
- `getConversationId(): String`
- `component1(): String`
- `component2(): String`
- `component3(): String`
- `component4(): int`
- `component5(): int`
- `getCompletionTokens(): int`
- `component6(): int`
- `component7(): String`
- `component8(): String`
- `getPromptTokens(): int`
- `getSessionTotalUsed(): int`
- `getGroupId(): String`
