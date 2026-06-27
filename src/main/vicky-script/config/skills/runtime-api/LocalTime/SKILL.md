---
name: LocalTime
description: Kotlin class: java.time.LocalTime.
group: runtime-api
---

# LocalTime

Kotlin class. Create instances with `new LocalTime(...)` or `LocalTime(...)`.

Full class: `java.time.LocalTime`

## Fields
- `MIN`: LocalTime (static)
- `MAX`: LocalTime (static)
- `MIDNIGHT`: LocalTime (static)
- `NOON`: LocalTime (static)
- `HOURS`: LocalTime[] (static)
- `HOURS_PER_DAY`: int (static)
- `MINUTES_PER_HOUR`: int (static)
- `MINUTES_PER_DAY`: int (static)
- `SECONDS_PER_MINUTE`: int (static)
- `SECONDS_PER_HOUR`: int (static)
- `SECONDS_PER_DAY`: int (static)
- `MILLIS_PER_SECOND`: long (static)
- `MILLIS_PER_DAY`: long (static)
- `MICROS_PER_SECOND`: long (static)
- `MICROS_PER_DAY`: long (static)
- `NANOS_PER_MILLI`: long (static)
- `NANOS_PER_SECOND`: long (static)
- `NANOS_PER_MINUTE`: long (static)
- `NANOS_PER_HOUR`: long (static)
- `NANOS_PER_DAY`: long (static)
- `serialVersionUID`: long (static)
- `hour`: byte
- `minute`: byte
- `second`: byte
- `nano`: int

## Methods
- `get(arg0: TemporalField): int`
- `equals(arg0: Object): boolean`
- `toString(): String`
- `hashCode(): int`
- `compareTo(arg0: LocalTime): int`
- `getLong(arg0: TemporalField): long`
- `format(arg0: DateTimeFormatter): String`
- `of(arg0: int, arg1: int, arg2: int): LocalTime`
- `of(arg0: int, arg1: int, arg2: int, arg3: int): LocalTime`
- `of(arg0: int, arg1: int): LocalTime`
- `from(arg0: TemporalAccessor): LocalTime`
- `isSupported(arg0: TemporalUnit): boolean`
- `isSupported(arg0: TemporalField): boolean`
- `parse(arg0: CharSequence): LocalTime`
- `parse(arg0: CharSequence, arg1: DateTimeFormatter): LocalTime`
- `with(arg0: TemporalAdjuster): LocalTime`
- `with(arg0: TemporalField, arg1: long): LocalTime`
- `query(arg0: TemporalQuery): Object`
- `range(arg0: TemporalField): ValueRange`
- `now(): LocalTime`
- `now(arg0: Clock): LocalTime`
- `now(arg0: ZoneId): LocalTime`
- `getNano(): int`
- `getHour(): int`
- `getMinute(): int`
- `getSecond(): int`
- `minus(arg0: TemporalAmount): LocalTime`
- `minus(arg0: long, arg1: TemporalUnit): LocalTime`
- `plus(arg0: TemporalAmount): LocalTime`
- `plus(arg0: long, arg1: TemporalUnit): LocalTime`
- `until(arg0: Temporal, arg1: TemporalUnit): long`
- `plusNanos(arg0: long): LocalTime`
- `plusSeconds(arg0: long): LocalTime`
- `plusHours(arg0: long): LocalTime`
- `plusMinutes(arg0: long): LocalTime`
- `minusHours(arg0: long): LocalTime`
- `minusMinutes(arg0: long): LocalTime`
- `minusSeconds(arg0: long): LocalTime`
- `minusNanos(arg0: long): LocalTime`
- `truncatedTo(arg0: TemporalUnit): LocalTime`
- `adjustInto(arg0: Temporal): Temporal`
- `ofInstant(arg0: Instant, arg1: ZoneId): LocalTime`
- `atOffset(arg0: ZoneOffset): OffsetTime`
- `isAfter(arg0: LocalTime): boolean`
- `isBefore(arg0: LocalTime): boolean`
- `toSecondOfDay(): int`
- `toEpochSecond(arg0: LocalDate, arg1: ZoneOffset): long`
- `ofNanoOfDay(arg0: long): LocalTime`
- `withHour(arg0: int): LocalTime`
- `withMinute(arg0: int): LocalTime`
- `withSecond(arg0: int): LocalTime`
- `withNano(arg0: int): LocalTime`
- `toNanoOfDay(): long`
- `ofSecondOfDay(arg0: long): LocalTime`
- `atDate(arg0: LocalDate): LocalDateTime`
