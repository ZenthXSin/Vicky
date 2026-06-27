// Use Rhino + bundled typescript.js to compile test.ts, dump JS to stdout.
load("src/main/vicky-script/resources/typescript.js");

var tsSource = readFile("config/scripts/test.ts");

var result = ts.transpileModule(tsSource, {
    compilerOptions: {
        target: ts.ScriptTarget.ES5,
        module: ts.ModuleKind.None,
        strict: false,
        esModuleInterop: true,
        skipLibCheck: true
    },
    fileName: "test.ts"
});

print(result.outputText);
