var name = "hello";
var description = "打招呼工具";
var parameters = {
  type: "object",
  properties: {
    name: { type: "string", description: "用户名" }
  },
  required: ["name"]
};

async function execute(ctx: any, args: any) {
  return {
    toAgent: "greeted " + args.name,
    userReply: "你好 " + args.name + "！"
  };
}
