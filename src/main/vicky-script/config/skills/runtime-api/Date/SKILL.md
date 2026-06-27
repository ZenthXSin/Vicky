---
name: Date
description: Kotlin class: java.util.Date.
group: runtime-api
---

# Date

Kotlin class. Create instances with `new Date(...)` or `Date(...)`.

Full class: `java.util.Date`

## Fields
- `gcal`: BaseCalendar (static)
- `jcal`: BaseCalendar (static)
- `fastTime`: long
- `cdate`: Date
- `defaultCenturyStart`: int (static)
- `serialVersionUID`: long (static)
- `wtb`: String[] (static)
- `ttb`: int[] (static)

## Constructors
- `Date(arg0: String)`
- `Date(arg0: int, arg1: int, arg2: int, arg3: int, arg4: int, arg5: int)`
- `Date(arg0: int, arg1: int, arg2: int, arg3: int, arg4: int)`
- `Date()`
- `Date(arg0: long)`
- `Date(arg0: int, arg1: int, arg2: int)`

## Methods
- `equals(arg0: Object): boolean`
- `toString(): String`
- `hashCode(): int`
- `clone(): Object`
- `compareTo(arg0: Date): int`
- `from(arg0: Instant): Date`
- `parse(arg0: String): long`
- `before(arg0: Date): boolean`
- `after(arg0: Date): boolean`
- `getTime(): long`
- `toInstant(): Instant`
- `UTC(arg0: int, arg1: int, arg2: int, arg3: int, arg4: int, arg5: int): long`
- `getYear(): int`
- `setTime(arg0: long): void`
- `getSeconds(): int`
- `getMonth(): int`
- `setDate(arg0: int): void`
- `setMonth(arg0: int): void`
- `getHours(): int`
- `setHours(arg0: int): void`
- `getMinutes(): int`
- `setMinutes(arg0: int): void`
- `setSeconds(arg0: int): void`
- `setYear(arg0: int): void`
- `getDate(): int`
- `getDay(): int`
- `toLocaleString(): String`
- `toGMTString(): String`
- `getTimezoneOffset(): int`
