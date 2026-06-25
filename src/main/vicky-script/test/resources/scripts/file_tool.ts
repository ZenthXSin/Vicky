var name = "file_read";
var description = "读取文件内容";
var parameters = {
  type: "object",
  properties: {
    path: { type: "string", description: "文件路径" }
  },
  required: ["path"]
};

async function execute(ctx: any, args: any) {
  var f = new File(args.path);
  var content = Files.readString(f.toPath());
  return {
    toAgent: content
  };
}
