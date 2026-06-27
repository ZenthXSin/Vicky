---
name: Files
description: Kotlin class: java.nio.file.Files.
group: runtime-api
---

# Files

Kotlin class. Create instances with `new Files(...)` or `Files(...)`.

Full class: `java.nio.file.Files`

## Fields
- `BUFFER_SIZE`: int (static)
- `DEFAULT_CREATE_OPTIONS`: Set (static)
- `JLA`: JavaLangAccess (static)

## Methods
- `size(arg0: Path): long`
- `isHidden(arg0: Path): boolean`
- `mismatch(arg0: Path, arg1: Path): long`
- `lines(arg0: Path): Stream`
- `lines(arg0: Path, arg1: Charset): Stream`
- `list(arg0: Path): Stream`
- `find(arg0: Path, arg1: int, arg2: BiPredicate, arg3: FileVisitOption[]): Stream`
- `write(arg0: Path, arg1: byte[], arg2: OpenOption[]): Path`
- `write(arg0: Path, arg1: Iterable, arg2: Charset, arg3: OpenOption[]): Path`
- `write(arg0: Path, arg1: Iterable, arg2: OpenOption[]): Path`
- `delete(arg0: Path): void`
- `readAllBytes(arg0: Path): byte[]`
- `copy(arg0: Path, arg1: Path, arg2: CopyOption[]): Path`
- `copy(arg0: Path, arg1: OutputStream): long`
- `copy(arg0: InputStream, arg1: Path, arg2: CopyOption[]): long`
- `walk(arg0: Path, arg1: FileVisitOption[]): Stream`
- `walk(arg0: Path, arg1: int, arg2: FileVisitOption[]): Stream`
- `exists(arg0: Path, arg1: LinkOption[]): boolean`
- `isDirectory(arg0: Path, arg1: LinkOption[]): boolean`
- `getOwner(arg0: Path, arg1: LinkOption[]): UserPrincipal`
- `getLastModifiedTime(arg0: Path, arg1: LinkOption[]): FileTime`
- `createDirectory(arg0: Path, arg1: FileAttribute[]): Path`
- `setLastModifiedTime(arg0: Path, arg1: FileTime): Path`
- `createTempFile(arg0: String, arg1: String, arg2: FileAttribute[]): Path`
- `createTempFile(arg0: Path, arg1: String, arg2: String, arg3: FileAttribute[]): Path`
- `isRegularFile(arg0: Path, arg1: LinkOption[]): boolean`
- `newInputStream(arg0: Path, arg1: OpenOption[]): InputStream`
- `newOutputStream(arg0: Path, arg1: OpenOption[]): OutputStream`
- `newByteChannel(arg0: Path, arg1: OpenOption[]): SeekableByteChannel`
- `newByteChannel(arg0: Path, arg1: Set, arg2: FileAttribute[]): SeekableByteChannel`
- `newDirectoryStream(arg0: Path, arg1: Filter): DirectoryStream`
- `newDirectoryStream(arg0: Path): DirectoryStream`
- `newDirectoryStream(arg0: Path, arg1: String): DirectoryStream`
- `createTempDirectory(arg0: Path, arg1: String, arg2: FileAttribute[]): Path`
- `createTempDirectory(arg0: String, arg1: FileAttribute[]): Path`
- `createSymbolicLink(arg0: Path, arg1: Path, arg2: FileAttribute[]): Path`
- `createLink(arg0: Path, arg1: Path): Path`
- `deleteIfExists(arg0: Path): boolean`
- `move(arg0: Path, arg1: Path, arg2: CopyOption[]): Path`
- `readSymbolicLink(arg0: Path): Path`
- `getFileStore(arg0: Path): FileStore`
- `isSameFile(arg0: Path, arg1: Path): boolean`
- `probeContentType(arg0: Path): String`
- `getFileAttributeView(arg0: Path, arg1: Class, arg2: LinkOption[]): FileAttributeView`
- `readAttributes(arg0: Path, arg1: String, arg2: LinkOption[]): Map`
- `readAttributes(arg0: Path, arg1: Class, arg2: LinkOption[]): BasicFileAttributes`
- `setAttribute(arg0: Path, arg1: String, arg2: Object, arg3: LinkOption[]): Path`
- `setOwner(arg0: Path, arg1: UserPrincipal): Path`
- `isSymbolicLink(arg0: Path): boolean`
- `isReadable(arg0: Path): boolean`
- `isWritable(arg0: Path): boolean`
- `isExecutable(arg0: Path): boolean`
- `walkFileTree(arg0: Path, arg1: Set, arg2: int, arg3: FileVisitor): Path`
- `walkFileTree(arg0: Path, arg1: FileVisitor): Path`
- `newBufferedReader(arg0: Path): BufferedReader`
- `newBufferedReader(arg0: Path, arg1: Charset): BufferedReader`
- `newBufferedWriter(arg0: Path, arg1: OpenOption[]): BufferedWriter`
- `newBufferedWriter(arg0: Path, arg1: Charset, arg2: OpenOption[]): BufferedWriter`
- `readString(arg0: Path, arg1: Charset): String`
- `readString(arg0: Path): String`
- `readAllLines(arg0: Path, arg1: Charset): List`
- `readAllLines(arg0: Path): List`
- `writeString(arg0: Path, arg1: CharSequence, arg2: Charset, arg3: OpenOption[]): Path`
- `writeString(arg0: Path, arg1: CharSequence, arg2: OpenOption[]): Path`
- `createFile(arg0: Path, arg1: FileAttribute[]): Path`
- `createDirectories(arg0: Path, arg1: FileAttribute[]): Path`
- `getAttribute(arg0: Path, arg1: String, arg2: LinkOption[]): Object`
- `getPosixFilePermissions(arg0: Path, arg1: LinkOption[]): Set`
- `setPosixFilePermissions(arg0: Path, arg1: Set): Path`
- `notExists(arg0: Path, arg1: LinkOption[]): boolean`
