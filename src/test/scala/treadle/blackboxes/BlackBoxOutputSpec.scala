// See LICENSE for license details.

package treadle.blackboxes

import firrtl.ir.Type
import treadle._
import org.scalatest.{FreeSpec, Matchers}
import treadle.executable._

//scalastyle:off magic.number

/**
  * Illustrate a black box that has multiple outputs
  * This one creates 3 outputs each with a different increment of the input
  */
class FanOutAdder extends ScalaBlackBox {
  override def name: String = "FanOutAdder"

  override def getOutput(inputValues: Seq[BigInt], tpe: Type, outputName: String): BigInt = {
    val inc = outputName match {
      case "out1" => 1
      case "out2" => 2
      case "out3" => 3
    }
    inputValues.head + BigInt(inc)
  }

  override def clockChange(transition: Transition, clockName: String): Unit = {}

  override def outputDependencies(outputName: String): Seq[String] = {
    outputName match {
      case "out1" => Seq("in", "clock")
      case "out2" => Seq("in")
      case "out3" => Seq("in")
      case _ => throw TreadleException(s"$name was asked for input dependency for unknown output $outputName")
    }
  }
}

class FanOutAdderFactory extends ScalaBlackBoxFactory {
  override def createInstance(instanceName: String, blackBoxName: String): Option[ScalaBlackBox] = {
    Some(add(new FanOutAdder))
  }
}

class BlackBoxCounter extends ScalaBlackBox {
  val name: String = "BlackBoxCounter"
  var counter = BigInt(0)
  var clearSet: Boolean = false

  def getOutput(inputValues: Seq[BigInt], tpe: Type, outputName: String): BigInt = {
    if(inputValues.head == Big1) {
      clearSet = true
      counter = 0
    }
    else {
      clearSet = false
    }
    counter
  }

  override def clockChange(transition: Transition, clockName: String): Unit = {
    if(transition == PositiveEdge) {
      if(! clearSet) counter += 1
    }
  }

  override def outputDependencies(outputName: String): Seq[String] = {
    Seq("clear")
  }
}

class BlackBoxCounterFactory extends ScalaBlackBoxFactory {
  override def createInstance(instanceName: String, blackBoxName: String): Option[ScalaBlackBox] = {
    Some(add(new BlackBoxCounter))
  }
}

class BlackBoxOutputSpec extends FreeSpec with Matchers {
  "this tests black box implmentation that have multiple outputs" - {
    val adderInput =
      """
        |circuit FanOutTest :
        |  extmodule FanOut :
        |    input clock : Clock
        |    output out1 : UInt<64>
        |    output out2 : UInt<64>
        |    output out3 : UInt<64>
        |    input in : UInt<64>
        |
        |
        |  module FanOutTest :
        |    input clock : Clock
        |    input reset : UInt<1>
        |    input in : UInt<64>
        |    output out1 : UInt<64>
        |    output out2 : UInt<64>
        |    output out3 : UInt<64>
        |
        |    inst fo of FanOut
        |    fo.in <= in
        |    fo.clock <= clock
        |    out1 <= fo.out1
        |    out2 <= fo.out2
        |    out3 <= fo.out3
      """.stripMargin

    "each output should hold a different values" in {

      val factory = new FanOutAdderFactory

      val tester = TreadleTester(adderInput, Seq(
        BlackBoxFactoriesAnnotation(Seq(factory)),
        RandomSeedAnnotation(1L)
      ))

      for(i <- 0 until 10) {
        tester.poke("in", i)
        tester.expect("out1", i + 1)
        tester.expect("out2", i + 2)
        tester.expect("out3", i + 3)
        tester.step()
      }
    }
  }

  "this tests a black box of an accumulator that implements reset" - {
    val input =
      """
        |circuit CounterTest :
        |  extmodule BlackBoxCounter :
        |    input clock : Clock
        |    output counter : UInt<64>
        |    input clear : UInt<1>
        |
        |
        |  module CounterTest :
        |    input clock : Clock
        |    input reset : UInt<1>
        |    input clear : UInt<64>
        |    output counter : UInt<64>
        |
        |    inst bbc of BlackBoxCounter
        |    bbc.clear <= clear
        |    bbc.clock <= clock
        |    counter <= bbc.counter
      """.stripMargin

    "each output should hold a different values" in {

      val factory = new BlackBoxCounterFactory

      val tester = TreadleTester(input, Seq(
        BlackBoxFactoriesAnnotation(Seq(factory))
      ))

      tester.poke("clear", 1)
      tester.step()
      tester.poke("clear", 0)

      for(i <- 0 until 10) {
        tester.expect("counter", i)
        tester.step()
      }
    }
  }
}
