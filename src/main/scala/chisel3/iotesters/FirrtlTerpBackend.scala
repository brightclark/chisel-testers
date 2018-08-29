// See LICENSE for license details.
package chisel3.iotesters

import chisel3._
import chisel3.experimental.{MultiIOModule, RawModule}
import chisel3.internal.InstanceId
import firrtl.{AnnotationSeq, CompilerNameAnnotation, FirrtlExecutionFailure, FirrtlExecutionSuccess}
import firrtl_interpreter._

private[iotesters] class FirrtlTerpBackend(dut: RawModule, firrtlIR: String, annotationSeq: AnnotationSeq)
  extends Backend(_seed = System.currentTimeMillis()) {
  val interpretiveTester = new InterpretiveTester(firrtlIR, annotationSeq)
  reset(5) // reset firrtl interpreter on construction

  private val portNames = dut.getPorts.flatMap { case chisel3.internal.firrtl.Port(id, dir) =>
    val pathName = id.pathName
    getDataNames(pathName.drop(pathName.indexOf('.') + 1), id)
  }.toMap

  def poke(signal: InstanceId, value: BigInt, off: Option[Int])
          (implicit logger: TestErrorLog, verbose: Boolean, base: Int): Unit = {
    signal match {
      case port: Bits =>
        val name = portNames(port)
        interpretiveTester.poke(name, value)
        if (verbose) logger info s"  POKE $name <- ${bigIntToStr(value, base)}"

      case mem: Mem[_] =>
        val memoryName = mem.pathName.split("""\.""").tail.mkString(".")
        interpretiveTester.pokeMemory(memoryName, off.getOrElse(0), value)
        if (verbose) logger info s"  POKE MEMORY $memoryName <- ${bigIntToStr(value, base)}"

      case _ =>
    }
  }

  def poke(signal: InstanceId, value: Int, off: Option[Int])
          (implicit logger: TestErrorLog, verbose: Boolean, base: Int): Unit = {
    poke(signal, BigInt(value), off)
  }

  def peek(signal: InstanceId, off: Option[Int])
          (implicit logger: TestErrorLog, verbose: Boolean, base: Int): BigInt = {
    signal match {
      case port: Bits =>
        val name = portNames(port)
        val result = interpretiveTester.peek(name)
        if (verbose) logger info s"  PEEK $name -> ${bigIntToStr(result, base)}"
        result

      case mem: Mem[_] =>
        val memoryName = mem.pathName.split("""\.""").tail.mkString(".")

        interpretiveTester.peekMemory(memoryName, off.getOrElse(0))

      case _ => BigInt(rnd.nextInt)
    }
  }

  def expect(signal: InstanceId, expected: BigInt, msg: => String)
            (implicit logger: TestErrorLog, verbose: Boolean, base: Int) : Boolean = {
    signal match {
      case port: Bits =>
        val name = portNames(port)
        val got = interpretiveTester.peek(name)
        val good = got == expected
        if (verbose || !good) logger info
           s"""EXPECT AT $stepNumber $msg  $name got ${bigIntToStr(got, base)} expected ${bigIntToStr(expected, base)}""" +
           s""" ${if (good) "PASS" else "FAIL"}"""
        if(good) interpretiveTester.expectationsMet += 1
        good
      case _ => false
    }
  }

  def expect(signal: InstanceId, expected: Int, msg: => String)
            (implicit logger: TestErrorLog, verbose: Boolean, base: Int) : Boolean = {
    expect(signal,BigInt(expected), msg)
  }

  def poke(path: String, value: BigInt)
          (implicit logger: TestErrorLog, verbose: Boolean, base: Int): Unit = {
    assert(false)
  }

  def peek(path: String)
          (implicit logger: TestErrorLog, verbose: Boolean, base: Int): BigInt = {
    assert(false)
    BigInt(rnd.nextInt)
  }

  def expect(path: String, expected: BigInt, msg: => String)
            (implicit logger: TestErrorLog, verbose: Boolean, base: Int) : Boolean = {
    assert(false)
    false
  }

  private var stepNumber: Long = 0L

  def step(n: Int)(implicit logger: TestErrorLog): Unit = {
    stepNumber += n
    interpretiveTester.step(n)
  }

  def reset(n: Int = 1): Unit = {
    interpretiveTester.poke("reset", 1)
    interpretiveTester.step(n)
    interpretiveTester.poke("reset", 0)
  }

  def finish(implicit logger: TestErrorLog): Unit = {
    interpretiveTester.report()
  }
}

private[iotesters] object setupFirrtlTerpBackend {
  def apply[T <: RawModule](dutGen: () => T, annotationSeq: AnnotationSeq): (T, Backend) = {
    val annotations = annotationSeq :+ CompilerNameAnnotation("low")

    chisel3.Driver.execute(Array.empty, dutGen, annotations) match {
      case ChiselExecutionSuccess(Some(circuit), _, Some(firrtlExecutionResult)) =>
        val dut = getTopModule(circuit).asInstanceOf[T]
        firrtlExecutionResult match {
          case FirrtlExecutionSuccess(_, compiledFirrtl) =>
            (dut, new FirrtlTerpBackend(dut, compiledFirrtl, annotations))
          case FirrtlExecutionFailure(message) =>
            throw new Exception(s"FirrtlBackend: failed firrtl compile message: $message")
        }
      case _ =>
        throw new Exception("Problem with compilation")
    }
  }
}
