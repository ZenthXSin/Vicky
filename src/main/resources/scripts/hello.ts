const config = new AgentConfig({
    model: new ModelId("deepseek-v4-flash"),
    apiKey: "sk-Nhxs7MO3HDspptIICNmgobNdmeSc4RcIM6Aa4FLxvqgxeM6S",
    baseUrl: "http://192.168.0.108:3000/v1",
    mode: AgentMode.VERBOSE,
    maxSteps: 6,
    agentMd: "你是 Vicky，一个简洁的助手。",
    debug: false,
    builtinTools: true,
    streaming: false,
});

const contextManager = new DefaultContextManager({
    store: new ConversationStore(),
    builder: new ContextBuilder(config.agentMd),
    compactor: new ContextCompactor(config, OpenAiClientFactory.create(config)),
});

const sink = new MessageSink((out: OutboundMessage) => {
    switch (out.type) {
        case "AgentReply": println(`[agent] ${out.content}`); break;
        case "ToolReply":  println(`[tool] ${out.content}`);  break;
        case "Debug":      println(`[debug] ${out.content}`); break;
        case "Think":      println(`[think] ${out.content}`); break;
    }
});

const authorizer = new ToolAuthorizer((userId: string, toolName: string) => {
    if (toolName === "shutdown") return userId === "admin";
    return true;
});

// 用 extend 动态生成 Agent 的具体子类。getXxx 是 Kotlin val 编译后的 JVM getter 名。
// extend(BaseClass, jsImpl, ...ctorArgs) → Java 实例，agent.receive() 等方法真正可用。
const agent = extend(Agent, {
    getContextManager: () => contextManager,
    getSink: () => sink,
    getAuthorizer: () => authorizer,
}, config, OpenAiClientFactory.create(config));

//println("[test] calling agent.receive...");
try {
//    agent.receive(new InboundMessage("user1", "你好","你好"));
//    println("[test] receive done");
} catch (e) {
//    println("[test] receive error: " + e);
}