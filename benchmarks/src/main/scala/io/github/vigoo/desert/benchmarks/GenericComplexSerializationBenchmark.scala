package io.github.vigoo.desert.benchmarks

import io.github.vigoo.desert.Evolution.FieldAdded
import java.util.concurrent.TimeUnit
import io.github.vigoo.desert._
import io.github.vigoo.desert.{BinaryCodec, evolutionSteps}
import io.github.vigoo.desert.shapeless._
import org.openjdk.jmh.annotations._

import scala.util.Random

@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
class GenericComplexSerializationBenchmark {
  import GenericComplexSerializationBenchmark._

  var testDocument: Root          = _
  var serializedData: Array[Byte] = _

  @Param(Array("12"))
  var size: Int = _

  @Param(Array("34253"))
  var seed: Long = _

  private def generateItem(s: Int)(implicit rnd: Random): Item =
    rnd.nextInt(3) match {
      case 0           => Text(rnd.nextString(25))
      case 1           =>
        Rectangle(
          rnd.nextDouble(),
          rnd.nextDouble(),
          rnd.nextDouble(),
          rnd.nextDouble(),
          Style(
            Color(rnd.nextInt().toByte, rnd.nextInt().toByte, rnd.nextInt().toByte),
            Color(rnd.nextInt().toByte, rnd.nextInt().toByte, rnd.nextInt().toByte)
          )
        )
      case 2 if s == 0 => Text(rnd.nextString(25))
      case _           => Block(generateItemMap(s - 1))
    }

  private def generateItemMap(s: Int)(implicit rnd: Random): Map[ItemId, Item] =
    if (s == 0) {
      Map.empty
    } else {
      val count = rnd.nextInt(s)
      (0 to count).map(_ => (ItemId(rnd.nextString(16)) -> generateItem(s))).toMap
    }

  @Setup
  def createTestDocument(): Unit = {
    implicit val random: Random = new Random(seed)

    println("Generating test document")
    testDocument = Root(
      title = "A random generated test document",
      items = generateItemMap(size)
    )

    println("Serializing test document")
    serializedData = serializeToArray(testDocument).toOption.get
//    println(s"Test document: $testDocument")
//    println(s"Serialized length: ${serializedData.length}")
  }

  @Benchmark
  def serializeComplexDataStructure(): Unit = {
    val data = serializeToArray(testDocument)
  }

  @Benchmark
  def deserializeComplexDataStructure(): Unit = {
    val result = deserializeFromArray[Root](serializedData)
  }
}

object GenericComplexSerializationBenchmark {

  case class ItemId(value: String) extends AnyVal
  object ItemId {
    implicit val codec: BinaryCodec[ItemId] = DerivedBinaryCodec.deriveForWrapper[ItemId]
  }

  case class Color(r: Byte, g: Byte, b: Byte)
  object Color {
    implicit val codec: BinaryCodec[Color] = DerivedBinaryCodec.derive
  }

  case class Style(background: Color, foreground: Color)
  object Style {
    implicit val codec: BinaryCodec[Style] = DerivedBinaryCodec.derive
  }

  sealed trait Item
  case class Text(value: String)                                                 extends Item
  @evolutionSteps(FieldAdded("style", Style(Color(0, 0, 0), Color(255.toByte, 255.toByte, 255.toByte))))
  case class Rectangle(x: Double, y: Double, w: Double, h: Double, style: Style) extends Item
  case class Block(items: Map[ItemId, Item])                                     extends Item

  object Item {
    implicit val textCodec: BinaryCodec[Text]           = DerivedBinaryCodec.derive
    implicit val rectangleCodec: BinaryCodec[Rectangle] =
      DerivedBinaryCodec.derive
    implicit val blockCodec: BinaryCodec[Block]         = DerivedBinaryCodec.derive
    implicit val itemCodec: BinaryCodec[Item]           = DerivedBinaryCodec.derive
  }

  case class Root(title: String, items: Map[ItemId, Item])
  object Root {
    implicit val codec: BinaryCodec[Root] = DerivedBinaryCodec.derive
  }
}
