---
name: LocalDateTime
description: Kotlin class: java.time.LocalDateTime.
group: runtime-api
---

# LocalDateTime

Kotlin class. Create instances with `new LocalDateTime(...)` or `LocalDateTime(...)`.

Full class: `java.time.LocalDateTime`

## Fields
- `MIN`: LocalDateTime (static)
- `MAX`: LocalDateTime (static)
- `serialVersionUID`: long (static)
- `date`: LocalDate
- `time`: LocalTime

## Methods
- `get(arg0: TemporalField): int`
- `equals(arg0: Object): boolean`
- `toString(): String`
- `hashCode(): int`
- `compareTo(arg0: ChronoLocalDateTime): int`
- `getLong(arg0: TemporalField): long`
- `format(arg0: DateTimeFormatter): String`
- `of(arg0: int, arg1: int, arg2: int, arg3: int, arg4: int): LocalDateTime`
- `of(arg0: int, arg1: Month, arg2: int, arg3: int, arg4: int, arg5: int, arg6: int): LocalDateTime`
- `of(arg0: int, arg1: Month, arg2: int, arg3: int, arg4: int, arg5: int): LocalDateTime`
- `of(arg0: int, arg1: Month, arg2: int, arg3: int, arg4: int): LocalDateTime`
- `of(arg0: LocalDate, arg1: LocalTime): LocalDateTime`
- `of(arg0: int, arg1: int, arg2: int, arg3: int, arg4: int, arg5: int, arg6: int): LocalDateTime`
- `of(arg0: int, arg1: int, arg2: int, arg3: int, arg4: int, arg5: int): LocalDateTime`
- `from(arg0: TemporalAccessor): LocalDateTime`
- `isSupported(arg0: TemporalUnit): boolean`
- `isSupported(arg0: TemporalField): boolean`
- `parse(arg0: CharSequence): LocalDateTime`
- `parse(arg0: CharSequence, arg1: DateTimeFormatter): LocalDateTime`
- `with(arg0: TemporalField, arg1: long): LocalDateTime`
- `with(arg0: TemporalAdjuster): LocalDateTime`
- `query(arg0: TemporalQuery): Object`
- `range(arg0: TemporalField): ValueRange`
- `now(): LocalDateTime`
- `now(arg0: Clock): LocalDateTime`
- `now(arg0: ZoneId): LocalDateTime`
- `getNano(): int`
- `ofEpochSecond(arg0: long, arg1: int, arg2: ZoneOffset): LocalDateTime`
- `getYear(): int`
- `getMonthValue(): int`
- `getDayOfMonth(): int`
- `getHour(): int`
- `getMinute(): int`
- `getSecond(): int`
- `minus(arg0: TemporalAmount): LocalDateTime`
- `minus(arg0: long, arg1: TemporalUnit): LocalDateTime`
- `plus(arg0: long, arg1: TemporalUnit): LocalDateTime`
- `plus(arg0: TemporalAmount): LocalDateTime`
- `until(arg0: Temporal, arg1: TemporalUnit): long`
- `plusNanos(arg0: long): LocalDateTime`
- `plusSeconds(arg0: long): LocalDateTime`
- `plusDays(arg0: long): LocalDateTime`
- `plusHours(arg0: long): LocalDateTime`
- `plusMinutes(arg0: long): LocalDateTime`
- `minusDays(arg0: long): LocalDateTime`
- `minusHours(arg0: long): LocalDateTime`
- `minusMinutes(arg0: long): LocalDateTime`
- `minusSeconds(arg0: long): LocalDateTime`
- `minusNanos(arg0: long): LocalDateTime`
- `truncatedTo(arg0: TemporalUnit): LocalDateTime`
- `adjustInto(arg0: Temporal): Temporal`
- `ofInstant(arg0: Instant, arg1: ZoneId): LocalDateTime`
- `atOffset(arg0: ZoneOffset): OffsetDateTime`
- `atZone(arg0: ZoneId): ZonedDateTime`
- `isAfter(arg0: ChronoLocalDateTime): boolean`
- `isBefore(arg0: ChronoLocalDateTime): boolean`
- `getMonth(): Month`
- `getDayOfWeek(): DayOfWeek`
- `getDayOfYear(): int`
- `withDayOfMonth(arg0: int): LocalDateTime`
- `withDayOfYear(arg0: int): LocalDateTime`
- `plusWeeks(arg0: long): LocalDateTime`
- `withMonth(arg0: int): LocalDateTime`
- `plusMonths(arg0: long): LocalDateTime`
- `withYear(arg0: int): LocalDateTime`
- `plusYears(arg0: long): LocalDateTime`
- `minusMonths(arg0: long): LocalDateTime`
- `toLocalTime(): LocalTime`
- `isEqual(arg0: ChronoLocalDateTime): boolean`
- `minusYears(arg0: long): LocalDateTime`
- `minusWeeks(arg0: long): LocalDateTime`
- `withHour(arg0: int): LocalDateTime`
- `withMinute(arg0: int): LocalDateTime`
- `withSecond(arg0: int): LocalDateTime`
- `withNano(arg0: int): LocalDateTime`
- `toLocalDate(): LocalDate`
