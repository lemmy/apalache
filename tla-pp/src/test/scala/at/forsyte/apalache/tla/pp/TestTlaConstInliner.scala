package at.forsyte.apalache.tla.pp

import at.forsyte.apalache.tla.lir.oper.TlaArithOper.{minus, plus}
import at.forsyte.apalache.tla.lir.transformations.impl.IdleTracker
import at.forsyte.apalache.tla.lir.values.TlaInt
import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.convenience.tla
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterEach, FunSuite, Matchers}
import at.forsyte.apalache.tla.lir.UntypedPredefs._

@RunWith(classOf[JUnitRunner])
class TestTlaConstInliner extends FunSuite with BeforeAndAfterEach with Matchers {
  // some expressions to play with
  private val primitiveValuedConst = tla.name("PrimitiveValuedConst")
  private val nonPrimitiveValuedConst = tla.name("NonPrimitiveValuedConst")
  private val nonConst = tla.name("x")
  private val primitiveValue = tla.int(42)
  // module-level constant names
  private val constants: Set[String] = Set(primitiveValuedConst.name, nonPrimitiveValuedConst.name)
  // constants mapped to primitive values
  private val constMap = Map(primitiveValuedConst.name -> primitiveValue.untyped().asInstanceOf[ValEx],
      nonConst.name -> primitiveValue.untyped().asInstanceOf[ValEx])
  // the inliner
  private var constInliner: TlaConstInliner = _

  override def beforeEach(): Unit = {
    constInliner = new TlaConstInliner(new IdleTracker(), constants)
  }

  test("inlines mapped NameEx constant") {
    val input = primitiveValuedConst
    val result = constInliner.replaceConstWithValue(constMap)(input)
    result shouldBe tla.int(42).untyped()
  }

  test("passes unmapped NameEx unchanged") {
    val input = nonPrimitiveValuedConst
    val result = constInliner.replaceConstWithValue(constMap)(input)
    result shouldBe input.untyped()
  }

  test("passes non-CONSTANT NameEx unchanged") {
    val input = nonConst
    val result = constInliner.replaceConstWithValue(constMap)(input)
    result shouldBe input.untyped()
  }

  test("inlines nested OperEx") {
    val input = tla.plus(nonConst, tla.minus(primitiveValuedConst, nonPrimitiveValuedConst))
    val result = constInliner.replaceConstWithValue(constMap)(input)
    // should replace nested `primitiveValuedConst` with `primitiveValue`
    result shouldBe tla.plus(nonConst, tla.minus(primitiveValue, nonPrimitiveValuedConst)).untyped()
  }

  test("inlines LetInEx body and operator declaration") {
    val body = tla.plus(nonConst, tla.minus(primitiveValuedConst, nonPrimitiveValuedConst))
    val operDecl = tla.declOp("A", tla.plus(primitiveValuedConst, nonPrimitiveValuedConst))
    val input = tla.letIn(body, operDecl.untypedOperDecl())
    val result = constInliner.replaceConstWithValue(constMap)(input)
    // should replace `primitiveValuedConst` with `primitiveValue` in body and operator declaration
    val expectedBody = tla.plus(nonConst, tla.minus(primitiveValue, nonPrimitiveValuedConst))
    val expectedOperDecl = tla.declOp("A", tla.plus(primitiveValue, nonPrimitiveValuedConst)).untypedOperDecl()
    result shouldBe tla.letIn(expectedBody, expectedOperDecl).untyped()
  }
}
