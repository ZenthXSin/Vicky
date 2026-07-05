---
name: CompletionResult
description: Kotlin class: org.example.vicky.agent.CompletionResult.
group: runtime-api
---

# CompletionResult

Kotlin class. Create instances with `new CompletionResult(...)` or `CompletionResult(...)`.

Full class: `org.example.vicky.agent.CompletionResult`

## Fields
- `message`: ChatMessage
- `promptTokens`: int
- `completionTokens`: int

## Constructors
- `CompletionResult(arg0: ChatMessage, arg1: int, arg2: int)`
- `CompletionResult(arg0: ChatMessage, arg1: int, arg2: int, arg3: int, arg4: DefaultConstructorMarker)`

## Methods
- `equals(arg0: Object): boolean`
- `toString(): String`
- `hashCode(): int`
- `getMessage(): ChatMessage`
- `copy(arg0: ChatMessage, arg1: int, arg2: int): CompletionResult`
- `component1(): ChatMessage`
- `component2(): int`
- `component3(): int`
- `getCompletionTokens(): int`
- `getPromptTokens(): int`
