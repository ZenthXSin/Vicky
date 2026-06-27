---
name: String
description: Kotlin class: java.lang.String.
group: runtime-api
---

# String

Kotlin class. Create instances with `new String(...)` or `String(...)`.

Full class: `java.lang.String`

## Fields
- `value`: byte[]
- `coder`: byte
- `hash`: int
- `hashIsZero`: boolean
- `serialVersionUID`: long (static)
- `COMPACT_STRINGS`: boolean (static)
- `serialPersistentFields`: ObjectStreamField[] (static)
- `REPL`: char (static)
- `CASE_INSENSITIVE_ORDER`: Comparator (static)
- `LATIN1`: byte (static)
- `UTF16`: byte (static)

## Constructors
- `String(arg0: StringBuilder)`
- `String(arg0: byte[], arg1: int, arg2: int, arg3: Charset)`
- `String(arg0: byte[], arg1: String)`
- `String(arg0: byte[], arg1: Charset)`
- `String(arg0: byte[], arg1: int, arg2: int)`
- `String(arg0: byte[])`
- `String(arg0: StringBuffer)`
- `String(arg0: char[], arg1: int, arg2: int)`
- `String(arg0: char[])`
- `String(arg0: String)`
- `String()`
- `String(arg0: byte[], arg1: int, arg2: int, arg3: String)`
- `String(arg0: byte[], arg1: int)`
- `String(arg0: byte[], arg1: int, arg2: int, arg3: int)`
- `String(arg0: int[], arg1: int, arg2: int)`

## Methods
- `equals(arg0: Object): boolean`
- `length(): int`
- `toString(): String`
- `hashCode(): int`
- `getChars(arg0: int, arg1: int, arg2: char[], arg3: int): void`
- `compareTo(arg0: String): int`
- `indexOf(arg0: String, arg1: int, arg2: int): int`
- `indexOf(arg0: String, arg1: int): int`
- `indexOf(arg0: int): int`
- `indexOf(arg0: int, arg1: int): int`
- `indexOf(arg0: int, arg1: int, arg2: int): int`
- `indexOf(arg0: String): int`
- `valueOf(arg0: long): String`
- `valueOf(arg0: char[]): String`
- `valueOf(arg0: Object): String`
- `valueOf(arg0: char[], arg1: int, arg2: int): String`
- `valueOf(arg0: float): String`
- `valueOf(arg0: double): String`
- `valueOf(arg0: char): String`
- `valueOf(arg0: boolean): String`
- `valueOf(arg0: int): String`
- `charAt(arg0: int): char`
- `codePointAt(arg0: int): int`
- `codePointBefore(arg0: int): int`
- `codePointCount(arg0: int, arg1: int): int`
- `offsetByCodePoints(arg0: int, arg1: int): int`
- `getBytes(): byte[]`
- `getBytes(arg0: int, arg1: int, arg2: byte[], arg3: int): void`
- `getBytes(arg0: String): byte[]`
- `getBytes(arg0: Charset): byte[]`
- `contentEquals(arg0: CharSequence): boolean`
- `contentEquals(arg0: StringBuffer): boolean`
- `regionMatches(arg0: int, arg1: String, arg2: int, arg3: int): boolean`
- `regionMatches(arg0: boolean, arg1: int, arg2: String, arg3: int, arg4: int): boolean`
- `startsWith(arg0: String): boolean`
- `startsWith(arg0: String, arg1: int): boolean`
- `lastIndexOf(arg0: int): int`
- `lastIndexOf(arg0: String): int`
- `lastIndexOf(arg0: int, arg1: int): int`
- `lastIndexOf(arg0: String, arg1: int): int`
- `substring(arg0: int, arg1: int): String`
- `substring(arg0: int): String`
- `isEmpty(): boolean`
- `replace(arg0: char, arg1: char): String`
- `replace(arg0: CharSequence, arg1: CharSequence): String`
- `matches(arg0: String): boolean`
- `replaceFirst(arg0: String, arg1: String): String`
- `replaceAll(arg0: String, arg1: String): String`
- `split(arg0: String): String[]`
- `split(arg0: String, arg1: int): String[]`
- `splitWithDelimiters(arg0: String, arg1: int): String[]`
- `join(arg0: CharSequence, arg1: Iterable): String`
- `join(arg0: CharSequence, arg1: CharSequence[]): String`
- `toLowerCase(): String`
- `toLowerCase(arg0: Locale): String`
- `toUpperCase(): String`
- `toUpperCase(arg0: Locale): String`
- `trim(): String`
- `strip(): String`
- `stripLeading(): String`
- `stripTrailing(): String`
- `lines(): Stream`
- `repeat(arg0: int): String`
- `isBlank(): boolean`
- `toCharArray(): char[]`
- `format(arg0: Locale, arg1: String, arg2: Object[]): String`
- `format(arg0: String, arg1: Object[]): String`
- `resolveConstantDesc(arg0: Lookup): String`
- `codePoints(): IntStream`
- `equalsIgnoreCase(arg0: String): boolean`
- `compareToIgnoreCase(arg0: String): int`
- `endsWith(arg0: String): boolean`
- `subSequence(arg0: int, arg1: int): CharSequence`
- `concat(arg0: String): String`
- `contains(arg0: CharSequence): boolean`
- `indent(arg0: int): String`
- `stripIndent(): String`
- `translateEscapes(): String`
- `chars(): IntStream`
- `transform(arg0: Function): Object`
- `formatted(arg0: Object[]): String`
- `copyValueOf(arg0: char[], arg1: int, arg2: int): String`
- `copyValueOf(arg0: char[]): String`
- `intern(): String`
- `describeConstable(): Optional`
