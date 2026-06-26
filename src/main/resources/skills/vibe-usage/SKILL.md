---
name: vibe-usage
description: Vibe 多阶段编排工具使用指南，教 agent 如何构造高质量任务描述并解读结果
---

# Vibe 多阶段编排使用指南

你是 Vicky 的任务编排助手。当用户提出复杂需求时，你使用 vibe 工具将任务分解为 5 个阶段执行。

## 何时使用 vibe

**使用 vibe 的场景**：
- 需求涉及多个步骤（代码 + 测试 + 文档）
- 需要先调研再执行的任务
- 用户明确要求"帮我规划"或"详细做一下"
- 涉及多个文件/模块的修改
- 需要质量审查的任务

**不要使用 vibe 的场景**：
- 简单问答（直接回答）
- 单步操作（用具体工具）
- 纯聊天（不需要编排）

## 如何构造高质量 request

request 是整个流水线的起点。质量越高，结果越好。

### 好的 request 特征

1. **明确目标**：说清楚要做什么，不是"帮我看看"而是"检查 src/ 下的 Kotlin 文件是否有未使用的 import"
2. **约束条件**：指定范围、语言、风格等限制
3. **验收标准**：怎样算完成

### 示例对比

**差**：`帮我写代码`
**好**：`在 Vicky 项目中创建一个 HTTP 健康检查工具，每 30 秒检测指定 URL，状态变化时通过 ctx.sendGroupMessage 通知群聊。用 Kotlin，放在 src/main/kotlin/ 下，参考现有 BuiltinToolImpl 的 @VickyTool 注解风格。`

**差**：`看看有没有 bug`
**好**：`审查 src/main/vicky-script/ 下的 ScriptEngine.kt 和 ScriptManager.kt，重点关注：1) Rhino Context 的 ThreadLocal 生命周期是否正确 2) 脚本加载/卸载时资源是否泄漏 3) 异常处理是否完整。`

## 五阶段流水线

### Stage 1 — 综合 (General)
- **做什么**：理解你的 request，拆分为具体子任务
- **期望输入**：你的 request 原文
- **输出**：任务列表 + 依赖关系

### Stage 2 — 规划 (Planning)
- **做什么**：设计执行方案，确定步骤顺序
- **期望输入**：request + 任务列表
- **输出**：详细执行计划 + 风险评估

### Stage 3 — 调查 (Investigation)
- **做什么**：搜索代码、读取文件、收集信息
- **期望输入**：request + 执行计划
- **输出**：调研结果 + 关键发现

### Stage 4 — 编写 (Writing)
- **做什么**：执行实际操作（写代码、创建文件等）
- **期望输入**：request + 计划 + 调研结果
- **输出**：操作记录 + 代码/文件变更

### Stage 5 — 复查 (Review)
- **做什么**：审查质量，决定通过或退回
- **期望输入**：request + 所有前序结果
- **输出**：审查意见 + pass/fail 判定

## 如何解读结果

vibe 工具返回格式化的结果：

```
# Vibe Pipeline Result

## ✓ Stage 1 — 综合
**Summary**: ...
（该阶段的详细输出）

## ✓ Stage 2 — 规划
...

---
**Tasks**: 3 | **Elapsed**: 45000ms | **Success**: true
```

- **Summary**：一句话概括，快速了解阶段成果
- **详细输出**：包含代码、分析、操作记录等实质内容
- **Tasks**：流水线创建的任务数
- **Elapsed**：总耗时（各阶段串行执行）
- **Success**：Review 阶段是否通过

### 如果 Review 退回（pass=false）

说明执行结果有问题。查看 Review 阶段的输出，找到具体问题后：
1. 修正问题
2. 可以再次调用 vibe 处理修正后的任务
3. 或者直接手动修复（如果问题明确）

## 最佳实践

1. **request 越具体越好**：模糊的 request 导致模糊的结果
2. **提供上下文**：如果任务涉及特定文件/模块，在 request 中说明
3. **设定范围**：避免 request 太大导致流水线过长
4. **信任流水线**：让各阶段自主决策，不要在 request 中过度约束执行方式
5. **审查结果**：即使 Success=true，也要检查 Review 阶段的意见
