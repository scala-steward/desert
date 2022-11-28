package io.github.vigoo.desert

import java.nio.charset.StandardCharsets
import java.util.UUID
import io.github.vigoo.desert.custom._
import io.github.vigoo.desert.internal.SerializerState.{StringAlreadyStored, StringId, StringIsNew}
import _root_.zio.{Chunk, NonEmptyChunk}
import _root_.zio.prelude.{Associative, NonEmptyList, Validation, ZSet}
import io.github.vigoo.desert.internal.AdtCodec

import java.time._
import scala.collection.{Factory, mutable}
import scala.collection.immutable.{ArraySeq, SortedMap, SortedSet}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/** Module containing implicit binary codecs for a lot of base types
  */
trait Codecs extends internal.TupleCodecs {

  implicit val byteCodec: BinaryCodec[Byte]     = BinaryCodec.define[Byte](value => writeByte(value))(readByte())
  implicit val shortCodec: BinaryCodec[Short]   = BinaryCodec.define[Short](value => writeShort(value))(readShort())
  implicit val intCodec: BinaryCodec[Int]       = BinaryCodec.define[Int](value => writeInt(value))(readInt())
  implicit val longCodec: BinaryCodec[Long]     = BinaryCodec.define[Long](value => writeLong(value))(readLong())
  implicit val floatCodec: BinaryCodec[Float]   = BinaryCodec.define[Float](value => writeFloat(value))(readFloat())
  implicit val doubleCodec: BinaryCodec[Double] = BinaryCodec.define[Double](value => writeDouble(value))(readDouble())

  private def createVarIntCodec(optimizeForPositive: Boolean): BinaryCodec[Int] =
    BinaryCodec.define[Int](value => writeVarInt(value, optimizeForPositive))(readVarInt(optimizeForPositive))
  val varIntOptimizedForPositiveCodec: BinaryCodec[Int]                         = createVarIntCodec(optimizeForPositive = true)
  val varIntCodec: BinaryCodec[Int]                                             = createVarIntCodec(optimizeForPositive = false)

  implicit val booleanCodec: BinaryCodec[Boolean] =
    BinaryCodec.define[Boolean](value => writeByte(if (value) 1 else 0))(readByte().map(_ != 0))

  implicit val unitCodec: BinaryCodec[Unit] =
    BinaryCodec.define[Unit](_ => finishSerializer())(finishDeserializerWith(()))

  implicit val charCodec: BinaryCodec[Char] = BinaryCodec.from(
    shortCodec.contramap(_.toShort),
    shortCodec.map(_.toChar)
  )

  implicit val stringCodec: BinaryCodec[String] = new BinaryCodec[String] {
    private val charset = StandardCharsets.UTF_8

    override def serialize(value: String): io.github.vigoo.desert.Ser[Unit] = {
      val raw = value.getBytes(charset)
      for {
        _ <- writeVarInt(raw.size, optimizeForPositive = false)
        _ <- writeBytes(raw)
      } yield ()
    }

    override def deserialize(): Deser[String] =
      for {
        count  <- readVarInt(optimizeForPositive = false)
        bytes  <- readBytes(count)
        string <- Deser.fromEither(
                    Try(new String(bytes, charset)).toEither.left.map(reason =>
                      DesertFailure.DeserializationFailure("Failed to decode string", Some(reason))
                    )
                  )
      } yield string
  }

  implicit val deduplicatedStringCodec: BinaryCodec[DeduplicatedString] = new BinaryCodec[DeduplicatedString] {
    private val charset = StandardCharsets.UTF_8

    override def serialize(value: DeduplicatedString): io.github.vigoo.desert.Ser[Unit] =
      storeString(value.string).flatMap {
        case StringAlreadyStored(id) =>
          writeVarInt(-id.value, optimizeForPositive = false)
        case StringIsNew(id)         =>
          stringCodec.serialize(value.string)
      }

    override def deserialize(): Deser[DeduplicatedString] =
      for {
        countOrId <- readVarInt(optimizeForPositive = false)
        result    <- if (countOrId < 0) {
                       val id = StringId(-countOrId)
                       getString(id).flatMap {
                         case Some(string) => finishDeserializerWith[String](string)
                         case None         => failDeserializerWith[String](DesertFailure.InvalidStringId(id))
                       }
                     } else {
                       for {
                         bytes  <- readBytes(countOrId)
                         string <- Deser.fromEither(
                                     Try(new String(bytes, charset)).toEither.left.map(reason =>
                                       DesertFailure.DeserializationFailure("Failed to decode string", Some(reason))
                                     )
                                   )
                         _      <- storeReadString(string)
                       } yield string
                     }
      } yield DeduplicatedString(result)
  }

  implicit val uuidCodec: BinaryCodec[UUID] = BinaryCodec.define((uuid: UUID) =>
    for {
      _ <- writeLong(uuid.getMostSignificantBits)
      _ <- writeLong(uuid.getLeastSignificantBits)
    } yield ()
  )(for {
    msb <- readLong()
    lsb <- readLong()
  } yield new UUID(msb, lsb))

  implicit val bigDecimalCodec: BinaryCodec[BigDecimal] =
    BinaryCodec.from(stringCodec.contramap(_.toString()), stringCodec.map(BigDecimal(_)))

  implicit val javaBigDecimalCodec: BinaryCodec[java.math.BigDecimal] =
    BinaryCodec.from(stringCodec.contramap(_.toString()), stringCodec.map(new java.math.BigDecimal(_)))

  implicit val javaBigIntegerCodec: BinaryCodec[java.math.BigInteger] =
    BinaryCodec.define({ (bigint: java.math.BigInteger) =>
      val array = bigint.toByteArray
      for {
        _ <- writeVarInt(array.length, optimizeForPositive = true)
        _ <- writeBytes(array)
      } yield ()
    })(
      for {
        length <- readVarInt(optimizeForPositive = true)
        bytes  <- readBytes(length)
      } yield new java.math.BigInteger(bytes)
    )

  implicit val bigIntCodec: BinaryCodec[BigInt] =
    BinaryCodec.from(
      javaBigIntegerCodec.contramap(_.bigInteger),
      javaBigIntegerCodec.map(BigInt(_))
    )

  implicit val dayOfWeekCodec: BinaryCodec[DayOfWeek] =
    BinaryCodec.from(
      byteCodec.contramap(_.getValue.toByte),
      byteCodec.map(n => DayOfWeek.of(n.toInt))
    )

  implicit val monthCodec: BinaryCodec[Month] =
    BinaryCodec.from(
      byteCodec.contramap(_.getValue.toByte),
      byteCodec.map(n => Month.of(n.toInt))
    )

  implicit val yearCodec: BinaryCodec[Year] =
    BinaryCodec.from(
      varIntOptimizedForPositiveCodec.contramap(_.getValue),
      varIntOptimizedForPositiveCodec.map(n => Year.of(n))
    )

  implicit val monthDayCodec: BinaryCodec[MonthDay] =
    BinaryCodec.define((monthDay: MonthDay) =>
      for {
        _ <- write(monthDay.getMonth)
        _ <- writeByte(monthDay.getDayOfMonth.toByte)
      } yield ()
    )(for {
      month      <- read[Month]()
      dayOfMonth <- readByte()
    } yield MonthDay.of(month, dayOfMonth.toInt))

  implicit val yearMonthCodec: BinaryCodec[YearMonth] =
    BinaryCodec.define((yearMonth: YearMonth) =>
      for {
        _ <- writeVarInt(yearMonth.getYear, optimizeForPositive = true)
        _ <- write(yearMonth.getMonth)
      } yield ()
    )(for {
      year  <- readVarInt(optimizeForPositive = true)
      month <- read[Month]()
    } yield YearMonth.of(year, month))

  implicit val periodCodec: BinaryCodec[Period] =
    BinaryCodec.define((period: Period) =>
      for {
        _ <- writeVarInt(period.getYears, optimizeForPositive = true)
        _ <- writeVarInt(period.getMonths, optimizeForPositive = true)
        _ <- writeVarInt(period.getDays, optimizeForPositive = true)
      } yield ()
    )(for {
      years  <- readVarInt(optimizeForPositive = true)
      months <- readVarInt(optimizeForPositive = true)
      days   <- readVarInt(optimizeForPositive = true)
    } yield Period.of(years, months, days))

  implicit val zoneIdCodec: BinaryCodec[ZoneId] =
    BinaryCodec.define[ZoneId] {
      case offset: ZoneOffset =>
        for {
          _ <- writeByte(0)
          _ <- writeVarInt(offset.getTotalSeconds, optimizeForPositive = false)
        } yield ()
      case region             =>
        for {
          _ <- writeByte(1)
          _ <- write(region.getId)
        } yield ()
    }(
      for {
        typ    <- readByte()
        result <-
          typ match {
            case 0 =>
              readVarInt(optimizeForPositive = false).map(totalSeconds => ZoneOffset.ofTotalSeconds(totalSeconds))
            case 1 => read[String]().map(id => ZoneId.of(id))
            case 2 => failDeserializerWith(DesertFailure.InvalidConstructorId(typ.toInt, "ZoneId"))
          }
      } yield result
    )

  implicit val zoneOffsetCodec: BinaryCodec[ZoneOffset] =
    BinaryCodec.from(
      varIntCodec.contramap(_.getTotalSeconds),
      varIntCodec.map(ZoneOffset.ofTotalSeconds)
    )

  implicit val durationCodec: BinaryCodec[Duration] =
    BinaryCodec.define((duration: Duration) =>
      for {
        _ <- writeLong(duration.getSeconds)
        _ <- writeInt(duration.getNano)
      } yield ()
    )(for {
      seconds <- readLong()
      nanos   <- readInt()
    } yield Duration.ofSeconds(seconds, nanos.toLong))

  implicit val instantCodec: BinaryCodec[Instant] =
    BinaryCodec.define((instant: Instant) =>
      for {
        _ <- writeLong(instant.getEpochSecond)
        _ <- writeInt(instant.getNano)
      } yield ()
    )(for {
      seconds <- readLong()
      nanos   <- readInt()
    } yield Instant.ofEpochSecond(seconds, nanos.toLong))

  implicit val localDateCodec: BinaryCodec[LocalDate] =
    BinaryCodec.define((localDate: LocalDate) =>
      for {
        _ <- writeVarInt(localDate.getYear, optimizeForPositive = true)
        _ <- write(localDate.getMonth)
        _ <- writeByte(localDate.getDayOfMonth.toByte)
      } yield ()
    )(for {
      year       <- readVarInt(optimizeForPositive = true)
      month      <- read[Month]()
      dayOfMonth <- readByte()
    } yield LocalDate.of(year, month, dayOfMonth.toInt))

  implicit val localTimeCodec: BinaryCodec[LocalTime] =
    BinaryCodec.define((localTime: LocalTime) =>
      for {
        _ <- writeByte(localTime.getHour.toByte)
        _ <- writeByte(localTime.getMinute.toByte)
        _ <- writeByte(localTime.getSecond.toByte)
        _ <- writeVarInt(localTime.getNano, optimizeForPositive = true)
      } yield ()
    )(for {
      hour   <- readByte()
      minute <- readByte()
      second <- readByte()
      nano   <- readVarInt(optimizeForPositive = true)
    } yield LocalTime.of(hour, minute, second, nano))

  implicit val localDateTimeCodec: BinaryCodec[LocalDateTime] =
    BinaryCodec.define((localDateTime: LocalDateTime) =>
      for {
        _ <- write(localDateTime.toLocalDate)
        _ <- write(localDateTime.toLocalTime)
      } yield ()
    )(for {
      date <- read[LocalDate]()
      time <- read[LocalTime]()
    } yield LocalDateTime.of(date, time))

  implicit val offsetTimeCodec: BinaryCodec[OffsetTime] =
    BinaryCodec.define((offsetTime: OffsetTime) =>
      for {
        _ <- write(offsetTime.toLocalTime)
        _ <- write(offsetTime.getOffset)
      } yield ()
    )(for {
      time   <- read[LocalTime]()
      offset <- read[ZoneOffset]()
    } yield OffsetTime.of(time, offset))

  implicit val offsetDateTimeCodec: BinaryCodec[OffsetDateTime] =
    BinaryCodec.define((offsetDateTime: OffsetDateTime) =>
      for {
        _ <- write(offsetDateTime.toLocalDateTime)
        _ <- write(offsetDateTime.getOffset)
      } yield ()
    )(for {
      dateTime <- read[LocalDateTime]()
      offset   <- read[ZoneOffset]()
    } yield OffsetDateTime.of(dateTime, offset))

  implicit val zonedDateTimeCodec: BinaryCodec[ZonedDateTime] =
    BinaryCodec.define((offsetDateTime: ZonedDateTime) =>
      for {
        _ <- write(offsetDateTime.toLocalDateTime)
        _ <- write(offsetDateTime.getOffset)
        _ <- write(offsetDateTime.getZone)
      } yield ()
    )(for {
      dateTime <- read[LocalDateTime]()
      offset   <- read[ZoneOffset]()
      zone     <- read[ZoneId]()
    } yield ZonedDateTime.ofStrict(dateTime, offset, zone))

  private[desert] final case class OptionBinaryCodec[T]()(implicit val innerCodec: BinaryCodec[T])
      extends BinaryCodec[Option[T]] {
    override def deserialize(): Deser[Option[T]] =
      for {
        isDefined <- read[Boolean]()
        result    <- if (isDefined) read[T]().map(Some.apply) else finishDeserializerWith(None)
      } yield result

    override def serialize(value: Option[T]): io.github.vigoo.desert.Ser[Unit] =
      value match {
        case Some(value) => write(true) *> write(value)
        case None        => write(false)
      }
  }

  implicit def optionCodec[T: BinaryCodec]: BinaryCodec[Option[T]] = OptionBinaryCodec[T]()

  // Throwable

  implicit val stackTraceElementCodec: BinaryCodec[StackTraceElement] =
    BinaryCodec.define[StackTraceElement](stackTraceElement =>
      for {
        _ <- writeByte(0) // version
        _ <- write(Option(stackTraceElement.getClassName))
        _ <- write(Option(stackTraceElement.getMethodName))
        _ <- write(Option(stackTraceElement.getFileName))
        _ <- writeVarInt(stackTraceElement.getLineNumber, optimizeForPositive = true)
      } yield ()
    )(
      for {
        _          <- readByte() // version
        className  <- read[Option[String]]()
        methodName <- read[Option[String]]()
        fileName   <- read[Option[String]]()
        lineNumber <- readVarInt(optimizeForPositive = true)
      } yield new StackTraceElement(className.orNull, methodName.orNull, fileName.orNull, lineNumber)
    )

  implicit def persistedThrowableCodec: BinaryCodec[PersistedThrowable] =
    new AdtCodec[PersistedThrowable, PersistedThrowable](
      evolutionSteps = Vector(Evolution.InitialVersion),
      typeName = "io.github.vigoo.desert.PersistedThrowable",
      constructors = Vector("PersistedThrowable"),
      transientFields = Map.empty,
      getSerializationCommands = (pt: PersistedThrowable) =>
        List(
          AdtCodec.SerializationCommand.WriteField("className", pt.className, () => stringCodec),
          AdtCodec.SerializationCommand.WriteField("message", pt.message, () => stringCodec),
          AdtCodec.SerializationCommand.WriteField("stackTrace", pt.stackTrace, () => arrayCodec[StackTraceElement]),
          AdtCodec.SerializationCommand.WriteField("cause", pt.cause, () => optionCodec(persistedThrowableCodec))
        ),
      deserializationCommands = List(
        AdtCodec.DeserializationCommand.Read(
          "className",
          () => stringCodec,
          (className: String, pt: PersistedThrowable) => pt.copy(className = className)
        ),
        AdtCodec.DeserializationCommand
          .Read("message", () => stringCodec, (message: String, pt: PersistedThrowable) => pt.copy(message = message)),
        AdtCodec.DeserializationCommand.Read(
          "stackTrace",
          () => arrayCodec[StackTraceElement],
          (stackTrace: Array[StackTraceElement], pt: PersistedThrowable) => pt.copy(stackTrace = stackTrace)
        ),
        AdtCodec.DeserializationCommand.ReadOptional(
          "cause",
          () => persistedThrowableCodec,
          () => optionCodec(persistedThrowableCodec),
          (cause: Option[PersistedThrowable], pt: PersistedThrowable) => pt.copy(cause = cause)
        )
      ),
      initialBuilderState = PersistedThrowable("", "", Array.empty, None),
      materialize = Right(_)
    )

  implicit val throwableCodec: BinaryCodec[Throwable] = BinaryCodec.from(
    persistedThrowableCodec.contramap(PersistedThrowable.apply),
    persistedThrowableCodec.map(persisted => persisted)
  )

  // Collections

  def iterableCodec[A: BinaryCodec, T <: Iterable[A]](implicit factory: Factory[A, T]): BinaryCodec[T] =
    new BinaryCodec[T] {
      override def deserialize(): Deser[T] =
        for {
          knownSize <- readVarInt(optimizeForPositive = false)
          result    <- if (knownSize == -1) {
                         deserializeWithUnknownSize()
                       } else {
                         deserializeWithKnownSize(knownSize)
                       }
        } yield result

      private def deserializeWithUnknownSize(): Deser[T] = {
        val builder = factory.newBuilder
        readAll(builder).map(_.result())
      }

      private def readAll(builder: mutable.Builder[A, T]): Deser[mutable.Builder[A, T]] =
        readNext().flatMap {
          case None       => finishDeserializerWith(builder)
          case Some(elem) => readAll(builder += elem)
        }

      private def readNext(): Deser[Option[A]] =
        read[Option[A]]()

      private def deserializeWithKnownSize(size: Int): Deser[T] = {
        val builder = factory.newBuilder
        builder.sizeHint(size)

        (0 until size)
          .foldLeft(finishDeserializerWith(builder)) { case (b, _) =>
            b.flatMap(b => read[A]().map(b += _))
          }
          .map(_.result())
      }

      override def serialize(value: T): io.github.vigoo.desert.Ser[Unit] =
        if (value.knownSize == -1) {
          serializeWithUnknownSize(value)
        } else {
          serializeWithKnownSize(value, value.knownSize)
        }

      private def serializeWithUnknownSize(value: T): io.github.vigoo.desert.Ser[Unit] =
        for {
          _ <- writeVarInt(-1, optimizeForPositive = false)
          _ <- value.foldRight(finishSerializer()) { case (elem, rest) =>
                 write(true) *> write(elem) *> rest
               }
          _ <- write(false)
        } yield ()

      private def serializeWithKnownSize(value: T, size: Int): io.github.vigoo.desert.Ser[Unit] =
        for {
          _ <- writeVarInt(size, optimizeForPositive = false)
          _ <- value.foldRight(finishSerializer()) { case (elem, rest) =>
                 write(elem) *> rest
               }
        } yield ()
    }

  implicit def wrappedArrayCodec[A: BinaryCodec: ClassTag]: BinaryCodec[ArraySeq[A]] = iterableCodec[A, ArraySeq[A]]

  implicit def arrayCodec[A: BinaryCodec: ClassTag]: BinaryCodec[Array[A]] = BinaryCodec.from(
    wrappedArrayCodec[A].contramap(arr => ArraySeq.unsafeWrapArray(arr)),
    wrappedArrayCodec[A].map(_.toArray[A])
  )

  implicit def listCodec[A: BinaryCodec]: BinaryCodec[List[A]] = iterableCodec[A, List[A]]

  implicit def vectorCodec[A: BinaryCodec]: BinaryCodec[Vector[A]] = iterableCodec[A, Vector[A]]

  implicit def setCodec[A: BinaryCodec]: BinaryCodec[Set[A]] = iterableCodec[A, Set[A]]

  implicit def sortedSetCodec[A: BinaryCodec: Ordering]: BinaryCodec[SortedSet[A]] = iterableCodec[A, SortedSet[A]]

  implicit def mapCodec[K: BinaryCodec, V: BinaryCodec]: BinaryCodec[Map[K, V]] = iterableCodec[(K, V), Map[K, V]]

  implicit def sortedMapCodec[K: BinaryCodec: Ordering, V: BinaryCodec]: BinaryCodec[SortedMap[K, V]] =
    iterableCodec[(K, V), SortedMap[K, V]]

  implicit def eitherCodec[L: BinaryCodec, R: BinaryCodec]: BinaryCodec[Either[L, R]] = new BinaryCodec[Either[L, R]] {
    override def deserialize(): Deser[Either[L, R]] =
      for {
        isRight <- read[Boolean]()
        result  <- if (isRight) read[R]().map(Right.apply) else read[L]().map(Left.apply)
      } yield result

    override def serialize(value: Either[L, R]): io.github.vigoo.desert.Ser[Unit] =
      value match {
        case Left(value)  => write(false) *> write(value)
        case Right(value) => write(true) *> write(value)
      }
  }

  implicit def tryCodec[A: BinaryCodec]: BinaryCodec[Try[A]] = new BinaryCodec[Try[A]] {
    override def deserialize(): Deser[Try[A]] =
      for {
        isSuccess <- read[Boolean]()
        result    <- if (isSuccess) read[A]().map(Success.apply) else read[Throwable]().map(Failure.apply)
      } yield result

    override def serialize(value: Try[A]): io.github.vigoo.desert.Ser[Unit] =
      value match {
        case Failure(reason) => write(false) *> write(reason)
        case Success(value)  => write(true) *> write(value)
      }
  }

  // ZIO prelude specific codecs

  implicit def nonEmptyChunkCodec[A: BinaryCodec]: BinaryCodec[NonEmptyChunk[A]] = {
    val inner = iterableCodec[A, Chunk[A]](BinaryCodec[A], Chunk.iterableFactory)
    BinaryCodec.from(
      inner.contramap(_.toChunk),
      () =>
        inner.deserialize().flatMap { chunk =>
          NonEmptyChunk.fromChunk(chunk) match {
            case Some(nonEmptyChunk) => finishDeserializerWith(nonEmptyChunk)
            case None                =>
              failDeserializerWith(DesertFailure.DeserializationFailure("Non empty chunk is serialized as empty", None))
          }
        }
    )
  }

  implicit def validationCodec[E: BinaryCodec, A: BinaryCodec]: BinaryCodec[Validation[E, A]] =
    new BinaryCodec[Validation[E, A]] {
      override def deserialize(): Deser[Validation[E, A]] =
        for {
          isValid <- read[Boolean]()
          result  <-
            if (isValid)
              read[A]().map(Validation.succeed[A])
            else
              read[NonEmptyChunk[E]]().map { errors =>
                Validation.validateAll(errors.map(Validation.fail)).map(x => x.asInstanceOf[A])
              }
        } yield result

      override def serialize(value: Validation[E, A]): io.github.vigoo.desert.Ser[Unit] =
        value match {
          case Validation.Failure(_, value) => write(false) *> write(value)
          case Validation.Success(_, value) => write(true) *> write(value)
        }
    }

  implicit def nonEmptyListCodec[A: BinaryCodec]: BinaryCodec[NonEmptyList[A]] = {
    val inner = listCodec[A]
    BinaryCodec.from(
      inner.contramap(_.toList),
      () =>
        inner.deserialize().flatMap {
          case x :: xs => finishDeserializerWith(NonEmptyList.fromIterable(x, xs))
          case Nil     =>
            failDeserializerWith(DesertFailure.DeserializationFailure("Non empty list is serialized as empty", None))
        }
    )
  }

  implicit def zsetCodec[A: BinaryCodec, B: BinaryCodec]: BinaryCodec[ZSet[A, B]] = {
    val inner = mapCodec[A, B]
    BinaryCodec.from(
      inner.contramap(_.toMap),
      inner.map(ZSet.fromMap)
    )
  }
}
