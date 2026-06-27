---
name: Path
description: Kotlin class: java.nio.file.Path.
group: runtime-api
---

# Path

Kotlin class. Create instances with `new Path(...)` or `Path(...)`.

Full class: `java.nio.file.Path`

## Methods
- `getName(arg0: int): Path`
- `equals(arg0: Object): boolean`
- `toString(): String`
- `hashCode(): int`
- `compareTo(arg0: Path): int`
- `startsWith(arg0: String): boolean`
- `startsWith(arg0: Path): boolean`
- `iterator(): Iterator`
- `of(arg0: String, arg1: String[]): Path`
- `of(arg0: URI): Path`
- `endsWith(arg0: String): boolean`
- `endsWith(arg0: Path): boolean`
- `register(arg0: WatchService, arg1: Kind[], arg2: Modifier[]): WatchKey`
- `register(arg0: WatchService, arg1: Kind[]): WatchKey`
- `isAbsolute(): boolean`
- `resolve(arg0: Path): Path`
- `resolve(arg0: String): Path`
- `getParent(): Path`
- `getRoot(): Path`
- `toRealPath(arg0: LinkOption[]): Path`
- `toFile(): File`
- `getFileName(): Path`
- `normalize(): Path`
- `getFileSystem(): FileSystem`
- `relativize(arg0: Path): Path`
- `getNameCount(): int`
- `toAbsolutePath(): Path`
- `resolveSibling(arg0: Path): Path`
- `resolveSibling(arg0: String): Path`
- `subpath(arg0: int, arg1: int): Path`
- `toUri(): URI`
