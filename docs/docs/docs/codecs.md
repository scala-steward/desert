---
layout: docs
title: Codecs
---

# Codecs

A `BinaryCodec[T]` defines both the _serializer_ and _deserializer_ for a given type:

```scala
trait BinaryCodec[T] extends BinarySerializer[T] with BinaryDeserializer[T]
```

### Primitive types

The `io.github.vigoo.desert` package defines a lot of implicit binary codecs for common types.

The following code examples demonstrate this and also shows how the binary representation looks like.

```scala mdoc
import io.github.vigoo.desert._
import io.github.vigoo.desert.shapeless._

import java.time._
import java.time.temporal.ChronoUnit
import scala.math._
```

```scala mdoc:serialized
val byte = serializeToArray(100.toByte)
```

```scala mdoc:serialized
val short = serializeToArray(100.toShort)
```

```scala mdoc:serialized
val int = serializeToArray(100)
```

```scala mdoc:serialized
val long = serializeToArray(100L)
```

```scala mdoc:serialized
val float = serializeToArray(3.14.toFloat)
```

```scala mdoc:serialized
val double = serializeToArray(3.14)
```

```scala mdoc:serialized
val bool = serializeToArray(true)
```

```scala mdoc:serialized
val unit = serializeToArray(())
```

```scala mdoc:serialized
val ch = serializeToArray('!')
```

```scala mdoc:serialized
val str = serializeToArray("Hello")
```

```scala mdoc:serialized
val uuid = serializeToArray(java.util.UUID.randomUUID())
``` 

```scala mdoc:serialized
val bd = serializeToArray(BigDecimal(1234567890.1234567890))
```

```scala mdoc:serialized
val bi = serializeToArray(BigInt(1234567890))
```

```scala mdoc:serialized
val dow = serializeToArray(DayOfWeek.SATURDAY)
```

```scala mdoc:serialized
val month = serializeToArray(Month.FEBRUARY)
```

```scala mdoc:serialized
val year = serializeToArray(Year.of(2022))
```

```scala mdoc:serialized
val monthDay = serializeToArray(MonthDay.of(12, 1))
```

```scala mdoc:serialized
val yearMonth = serializeToArray(YearMonth.of(2022, 12))
```

```scala mdoc:serialized
val period = serializeToArray(Period.ofWeeks(3))
```

```scala mdoc:serialized
val zoneOffset = serializeToArray(ZoneOffset.UTC)
```

```scala mdoc:serialized
val duration = serializeToArray(Duration.of(123, ChronoUnit.SECONDS))
```

```scala mdoc:serialized
val instant = serializeToArray(Instant.parse("2022-12-01T11:11:00Z"))
```

```scala mdoc:serialized
val localDate = serializeToArray(LocalDate.of(2022, 12, 1))
```

```scala mdoc:serialized
val localTime = serializeToArray(LocalTime.of(11, 11))
```

```scala mdoc:serialized
val localDateTime = serializeToArray(LocalDateTime.of(2022, 12, 1, 11, 11, 0))
```

```scala mdoc:serialized
val offsetDateTime = serializeToArray(OffsetDateTime.of(2022, 12, 1, 11, 11, 0, 0, ZoneOffset.UTC))
```

```scala mdoc:serialized
val zonedDateTime = serializeToArray(ZonedDateTime.of(2022, 12, 1, 11, 11, 0, 0, ZoneOffset.UTC))
```

### Option, Either, Try, Validation

Common types such as `Option` and `Either` are also supported out of the box. For `Try` it
also has a codec for arbitrary `Throwable` instances, although deserializing it does not recreate
the original throwable just a `PersistedThrowable` instance. In practice this is a much safer approach
than trying to recreate the same exception via reflection.

```scala mdoc
import scala.collection.immutable.SortedSet
import scala.util._
import zio.NonEmptyChunk
import zio.prelude.Validation
```

```scala mdoc:serialized
val none = serializeToArray[Option[Int]](None)
```

```scala mdoc:serialized
val some = serializeToArray[Option[Int]](Some(100))
```

```scala mdoc:serialized
val left = serializeToArray[Either[Boolean, Int]](Left(true))
```

```scala mdoc:serialized
val right = serializeToArray[Either[Boolean, Int]](Right(100))
```

```scala mdoc:serialized
val valid = serializeToArray[Validation[String, Int]](Validation.succeed(100))
```

```scala mdoc:serialized
val invalid = serializeToArray[Validation[String, Int]](Validation.failNonEmptyChunk(NonEmptyChunk("error")))
```

```scala mdoc:silent
val fail = serializeToArray[Try[Int]](Failure(new RuntimeException("Test exception")))
```

```scala mdoc
val failDeser = fail.flatMap(data => deserializeFromArray[Try[Int]](data))
```

```scala mdoc:serialized
val success = serializeToArray[Try[Int]](Success(100))
```

### Collections

There is a generic `iterableCodec` that can be used to define implicit collection codecs based on
the Scala 2.13 collection API. For example this is how the `vectorCodec` is defined:

```scala
implicit def vectorCodec[A: BinaryCodec]: BinaryCodec[Vector[A]] = iterableCodec[A, Vector[A]]
```

All these collection codecs have one of the two possible representation. If the size is known in advance
then it is the number of elements followed by all the items in iteration order, otherwise it is a flat
list of all the elements wrapped in `Option[T]`. `Vector` and `List` are good examples for the two:

```scala mdoc:serialized
val vec = serializeToArray(Vector(1, 2, 3, 4))
```

```scala mdoc:serialized
val lst = serializeToArray(List(1, 2, 3, 4))
```  

Other supported collection types in the `codecs` package:

```scala mdoc
import zio.NonEmptyChunk
import zio.prelude.NonEmptyList
import zio.prelude.ZSet
```

```scala mdoc:serialized
val arr = serializeToArray(Array(1, 2, 3, 4))
```

```scala mdoc:serialized
val set = serializeToArray(Set(1, 2, 3, 4))
```

```scala mdoc:serialized
val sortedSet = serializeToArray(SortedSet(1, 2, 3, 4))
```

```scala mdoc:serialized
val nec = serializeToArray(NonEmptyChunk(1, 2, 3, 4))
```

```scala mdoc:serialized
val nel = serializeToArray(NonEmptyList(1, 2, 3, 4))
```

```scala mdoc:serialized
val nes = serializeToArray(ZSet(1, 2, 3, 4))
```

### String deduplication

For strings the library have a simple deduplication system, without sacrificing any extra
bytes for cases when strings are not duplicate. In general, the strings are encoded by a variable length
int representing the length of the string in bytes, followed by its UTF-8 encoding.
When deduplication is enabled, each serialized
string gets an ID and if it is serialized once more in the same stream, a negative number in place of the
length identifies it.

```scala mdoc:serialized
val twoStrings1 = serializeToArray(List("Hello", "Hello"))
```

```scala mdoc:serialized
val twoStrings2 = serializeToArray(List(DeduplicatedString("Hello"), DeduplicatedString("Hello")))
```

It is not turned on by default because it breaks backward compatibility when evolving data structures.
If a new string field is added, old versions of the application will skip it and would not assign the
same ID to the string if it is first seen.

It is enabled internally in desert for some cases, and can be used in _custom serializers_ freely.

### Tuples

The elements of tuples are serialized flat and the whole tuple gets prefixed by `0`, which makes them
compatible with simple _case classes_:

```scala mdoc:serialized
val tup = serializeToArray((1, 2, 3)) 
```

### Maps

`Map`, `SortedMap` and `NonEmptyMap` are just another `iterableCodec` built on top of the _tuple support_
for serializing an iteration of key-value pairs:

```scala mdoc
import scala.collection.immutable.SortedMap
```

```scala mdoc:serialized
val map = serializeToArray(Map(1 -> "x", 2 -> "y"))
```

```scala mdoc:serialized
val sortedmap = serializeToArray(SortedMap(1 -> "x", 2 -> "y"))
```

### Generic codecs for ADTs

There is a generic derivable codec for algebraic data types, with support for [evolving the type](evolution)
during the lifecycle of the application.

For _case classes_ the representation is the same as for tuples:

```scala mdoc
case class Point(x: Int, y: Int, z: Int)
object Point {
  implicit val codec: BinaryCodec[Point] = DerivedBinaryCodec.derive
}
```

```scala mdoc:serialized
val pt = serializeToArray(Point(1, 2, 3))
```

Note that there is no `@evolutionSteps` annotation used for the type. In this case the only additional storage
cost is a single `0` byte on the beginning just like with tuples. The **evolution steps** are explained on
a [separate section](evolution).

For _sum types_ the codec is not automatically derived for all the constructors when using the _Shapeless_ based
derivation. This has mostly historical reasons, as previous versions required passing the _evolution steps_ as
parameters to the `derive` method. The new _ZIO Schema_ based derivation does not have this limitation.

Other than that it works the same way, with `derive`:

```scala mdoc
sealed trait Drink
case class Beer(typ: String) extends Drink
case object Water extends Drink

object Drink {
  implicit val beerCodec: BinaryCodec[Beer] = DerivedBinaryCodec.derive
  implicit val waterCodec: BinaryCodec[Water.type] = DerivedBinaryCodec.derive
  implicit val codec: BinaryCodec[Drink] = DerivedBinaryCodec.derive
}
```

```scala mdoc:serialized
val a = serializeToArray[Drink](Beer("X"))
```

```scala mdoc:serialized
val b = serializeToArray[Drink](Water)
```

### Transient fields in generic codecs

It is possible to mark some fields of a _case class_ as **transient**:

```scala mdoc
case class Point2(x: Int, y: Int, z: Int, @transientField(None) cachedDistance: Option[Double])
object Point2 {
  implicit val codec: BinaryCodec[Point2] = DerivedBinaryCodec.derive
}
```

```scala mdoc:serialized
val serializedPt2 = serializeToArray(Point2(1, 2, 3, Some(3.7416)))
```

```scala mdoc
val pt2 = for {
  data <- serializedPt2
  result <- deserializeFromArray[Point2](data)
} yield result
```

Transient fields are not being serialized and they get a default value contained by the annotation
during deserialization. Note that the default value is not type checked during compilation, if
it does not match the field type it causes runtime error.

### Transient constructors in generic codecs

It is possible to mark whole constructors as **transient**:

```scala mdoc
sealed trait Cases
@transientConstructor case class Case1() extends Cases
case class Case2() extends Cases

object Cases {
  implicit val case2Codec: BinaryCodec[Case2] = DerivedBinaryCodec.derive
  implicit val codec: BinaryCodec[Cases] = DerivedBinaryCodec.derive
}
```

```scala mdoc:serialized
val cs1 = serializeToArray[Cases](Case1())
```

```scala mdoc:serialized
val cs2 = serializeToArray[Cases](Case2())
```

Transient constructors cannot be serialized. A common use case is for remote accessible actors where
some actor messages are known to be local only. By marking them as transient they can hold non-serializable data
without breaking the serialization of the other, remote messages.

### Generic codecs for value type wrappers

It is a good practice to use zero-cost value type wrappers around primitive types to represent
the intention in the type system. `desert` can derive binary codecs for these too:

```scala mdoc
case class DocumentId(id: Long) // extends AnyVal
object DocumentId {
  implicit val codec: BinaryCodec[DocumentId] = DerivedBinaryCodec.deriveForWrapper
}
```

```scala mdoc:serialized
val id = serializeToArray(DocumentId(100))
``` 

### Custom codecs

The _serialization_ is a simple scala function using an implicit serialization context:

```scala
def serialize(value: T)(implicit context: SerializationContext): Unit
```

while the _deserialization_ is

```scala
def deserialize()(implicit ctx: DeserializationContext): T
```

The `io.github.vigoo.desert.custom` package contains a set of serialization and
deserialization functions, all requiring the implicit contexts, that can be uesd
to implement custom codecs.

By implementing the `BinaryCodec` trait it is possible to define a fully custom codec. In the following
example we define a data type capable of representing cyclic graphs via a mutable `next` field, and
a custom codec for deserializing it. It also shows that built-in support for tracking _object references_
which is not used by the generic codecs but can be used in scenarios like this.

```scala mdoc
import cats.instances.either._
import io.github.vigoo.desert.custom._

  final class Node(val label: String,
                   var next: Option[Node]) {
    override def toString: String = 
      next match {
       case Some(n) => s"<$label -> ${n.label}>"
       case None => s"<$label>"
      }
  }
  object Node {
    implicit lazy val codec: BinaryCodec[Node] =
      new BinaryCodec[Node] {
        override def serialize(value: Node)(implicit context: SerializationContext): Unit = {
          write(value.label) // write the label using the built-in string codec
          value.next match {
            case Some(next) =>
              write(true)  // next is defined (built-in boolean codec)
              storeRefOrObject(next) // store ref-id or serialize next
            case None       =>
              write(false) // next is undefined (built-in boolean codec)
          }
        }
        
        override def deserialize()(implicit ctx: DeserializationContext): Node = {
          val label   = read[String]()        // read the label using the built-in string codec
          val result  = new Node(label, None) // create the new node
          storeReadRef(result)                // store the node in the reference map
          val hasNext = read[Boolean]()       // read if 'next' is defined
          if (hasNext) {
            // Read next with reference-id support and mutate the result
            val next = readRefOrValue[Node](storeReadReference = false)
            result.next = Some(next)
          }
          result
        }
    }     
  }

  case class Root(node: Node)
  object Root {
    implicit val codec: BinaryCodec[Root] = new BinaryCodec[Root] {
      override def deserialize()(implicit ctx: DeserializationContext): Root =
        Root(readRefOrValue[Node](storeReadReference = false))

      override def serialize(value: Root)(implicit context: SerializationContext): Unit =
        storeRefOrObject(value.node)
    }
  }

val nodeA = new Node("a", None)
val nodeB = new Node("a", None)
val nodeC = new Node("a", None)
nodeA.next = Some(nodeB)
nodeB.next = Some(nodeC)
nodeC.next = Some(nodeA)
```

```scala mdoc:serialized
val result = serializeToArray(Root(nodeA))
``` 

### Monadic custom codecs

Previous versions of `desert` exposed a monadic serializer/deserializer API based on `ZPure` with the following
types:

```scala
type Ser[T] = ZPure[Nothing, SerializerState, SerializerState, SerializationEnv, DesertFailure, T]
type Deser[T] = ZPure[Nothing, SerializerState, SerializerState, DeserializationEnv, DesertFailure, T]
```

For compatibility, the library still defines the monadic version of the serialization functions in the
`io.github.vigoo.desert.custom.pure` package.

A monadic serializer or deserializer can be converted to a `BinarySerializer` or `BinaryDeserializer` using the
`fromPure` method.

To achieve higher performance, it is recommended to implement custom codecs using the low level serialization API.
