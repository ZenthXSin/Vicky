---
name: Period
description: Kotlin class: java.time.Period.
group: runtime-api
---

# Period

Kotlin class. Create instances with `new Period(...)` or `Period(...)`.

Full class: `java.time.Period`

## Fields
- `ZERO`: Period (static)
- `serialVersionUID`: long (static)
- `PATTERN`: Pattern (static)
- `SUPPORTED_UNITS`: List (static)
- `years`: int
- `months`: int
- `days`: int

## Methods
- `get(arg0: TemporalUnit): long`
- `equals(arg0: Object): boolean`
- `toString(): String`
- `hashCode(): int`
- `of(arg0: int, arg1: int, arg2: int): Period`
- `from(arg0: TemporalAmount): Period`
- `parse(arg0: CharSequence): Period`
- `between(arg0: LocalDate, arg1: LocalDate): Period`
- `normalized(): Period`
- `isZero(): boolean`
- `getMonths(): int`
- `isNegative(): boolean`
- `minus(arg0: TemporalAmount): Period`
- `plus(arg0: TemporalAmount): Period`
- `getUnits(): List`
- `negated(): Period`
- `multipliedBy(arg0: int): Period`
- `plusDays(arg0: long): Period`
- `ofDays(arg0: int): Period`
- `minusDays(arg0: long): Period`
- `addTo(arg0: Temporal): Temporal`
- `subtractFrom(arg0: Temporal): Temporal`
- `plusMonths(arg0: long): Period`
- `toTotalMonths(): long`
- `getDays(): int`
- `plusYears(arg0: long): Period`
- `minusMonths(arg0: long): Period`
- `getChronology(): IsoChronology`
- `minusYears(arg0: long): Period`
- `getYears(): int`
- `ofYears(arg0: int): Period`
- `ofMonths(arg0: int): Period`
- `ofWeeks(arg0: int): Period`
- `withYears(arg0: int): Period`
- `withMonths(arg0: int): Period`
- `withDays(arg0: int): Period`
