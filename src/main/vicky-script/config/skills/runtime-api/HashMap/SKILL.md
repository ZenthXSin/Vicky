---
name: HashMap
description: Kotlin class: java.util.HashMap.
group: runtime-api
---

# HashMap

Kotlin class. Create instances with `new HashMap(...)` or `HashMap(...)`.

Full class: `java.util.HashMap`

## Fields
- `serialVersionUID`: long (static)
- `DEFAULT_INITIAL_CAPACITY`: int (static)
- `MAXIMUM_CAPACITY`: int (static)
- `DEFAULT_LOAD_FACTOR`: float (static)
- `TREEIFY_THRESHOLD`: int (static)
- `UNTREEIFY_THRESHOLD`: int (static)
- `MIN_TREEIFY_CAPACITY`: int (static)
- `table`: Node[]
- `entrySet`: Set
- `size`: int
- `modCount`: int
- `threshold`: int
- `loadFactor`: float

## Constructors
- `HashMap(arg0: int)`
- `HashMap(arg0: int, arg1: float)`
- `HashMap()`
- `HashMap(arg0: Map)`

## Methods
- `remove(arg0: Object): Object`
- `remove(arg0: Object, arg1: Object): boolean`
- `size(): int`
- `get(arg0: Object): Object`
- `put(arg0: Object, arg1: Object): Object`
- `values(): Collection`
- `clone(): Object`
- `clear(): void`
- `isEmpty(): boolean`
- `replace(arg0: Object, arg1: Object): Object`
- `replace(arg0: Object, arg1: Object, arg2: Object): boolean`
- `replaceAll(arg0: BiFunction): void`
- `merge(arg0: Object, arg1: Object, arg2: BiFunction): Object`
- `newHashMap(arg0: int): HashMap`
- `entrySet(): Set`
- `putAll(arg0: Map): void`
- `putIfAbsent(arg0: Object, arg1: Object): Object`
- `compute(arg0: Object, arg1: BiFunction): Object`
- `forEach(arg0: BiConsumer): void`
- `containsKey(arg0: Object): boolean`
- `computeIfAbsent(arg0: Object, arg1: Function): Object`
- `keySet(): Set`
- `containsValue(arg0: Object): boolean`
- `getOrDefault(arg0: Object, arg1: Object): Object`
- `computeIfPresent(arg0: Object, arg1: BiFunction): Object`
