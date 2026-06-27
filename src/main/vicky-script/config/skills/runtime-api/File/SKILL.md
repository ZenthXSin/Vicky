---
name: File
description: Kotlin class: java.io.File.
group: runtime-api
---

# File

Kotlin class. Create instances with `new File(...)` or `File(...)`.

Full class: `java.io.File`

## Fields
- `FS`: FileSystem (static)
- `path`: String
- `status`: PathStatus
- `prefixLength`: int
- `separatorChar`: char (static)
- `separator`: String (static)
- `pathSeparatorChar`: char (static)
- `pathSeparator`: String (static)
- `UNSAFE`: Unsafe (static)
- `PATH_OFFSET`: long (static)
- `PREFIX_LENGTH_OFFSET`: long (static)
- `serialVersionUID`: long (static)
- `filePath`: Path

## Constructors
- `File(arg0: String)`
- `File(arg0: String, arg1: String)`
- `File(arg0: URI)`
- `File(arg0: File, arg1: String)`

## Methods
- `getName(): String`
- `equals(arg0: Object): boolean`
- `length(): long`
- `toString(): String`
- `hashCode(): int`
- `isHidden(): boolean`
- `compareTo(arg0: File): int`
- `list(): String[]`
- `list(arg0: FilenameFilter): String[]`
- `isAbsolute(): boolean`
- `getParent(): String`
- `delete(): boolean`
- `setReadOnly(): boolean`
- `canRead(): boolean`
- `getPath(): String`
- `toURI(): URI`
- `toURL(): URL`
- `getAbsolutePath(): String`
- `exists(): boolean`
- `createNewFile(): boolean`
- `renameTo(arg0: File): boolean`
- `isDirectory(): boolean`
- `getCanonicalPath(): String`
- `getAbsoluteFile(): File`
- `mkdir(): boolean`
- `getCanonicalFile(): File`
- `getParentFile(): File`
- `mkdirs(): boolean`
- `setWritable(arg0: boolean): boolean`
- `setWritable(arg0: boolean, arg1: boolean): boolean`
- `setReadable(arg0: boolean, arg1: boolean): boolean`
- `setReadable(arg0: boolean): boolean`
- `setExecutable(arg0: boolean, arg1: boolean): boolean`
- `setExecutable(arg0: boolean): boolean`
- `listRoots(): File[]`
- `createTempFile(arg0: String, arg1: String): File`
- `createTempFile(arg0: String, arg1: String, arg2: File): File`
- `canWrite(): boolean`
- `isFile(): boolean`
- `lastModified(): long`
- `deleteOnExit(): void`
- `listFiles(arg0: FileFilter): File[]`
- `listFiles(arg0: FilenameFilter): File[]`
- `listFiles(): File[]`
- `setLastModified(arg0: long): boolean`
- `canExecute(): boolean`
- `getTotalSpace(): long`
- `getFreeSpace(): long`
- `getUsableSpace(): long`
- `toPath(): Path`
