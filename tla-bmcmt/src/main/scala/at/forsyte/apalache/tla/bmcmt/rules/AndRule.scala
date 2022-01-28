package at.forsyte.apalache.tla.bmcmt.rules

import at.forsyte.apalache.tla.bmcmt._
import at.forsyte.apalache.tla.bmcmt.rewriter.ConstSimplifierForSmt
import at.forsyte.apalache.tla.bmcmt.types.BoolT
import at.forsyte.apalache.tla.lir.TypedPredefs._
import at.forsyte.apalache.tla.lir.convenience.tla
import at.forsyte.apalache.tla.lir.oper.TlaBoolOper
import at.forsyte.apalache.tla.lir.{BoolT1, OperEx, TlaEx, ValEx}

/**
 * Implements the rule for conjunction. Similar to TLC, we short-circuit A /\ B as IF A THEN B ELSE FALSE.
 * This allows us to introduce an optimization on-the-fly for the conjunctions that were marked with a hint.
 * In this optimization, we push the context, assume A and check satisfiability of the SMT context.
 * If the context is unsat, we immediately return FALSE. Otherwise, we pop the context and continue.
 *
 * @author Igor Konnov
 */
class AndRule(rewriter: SymbStateRewriter) extends RewritingRule {
  private val simplifier = new ConstSimplifierForSmt()
  private val boolTypes = Map("b" -> BoolT1())

  override def isApplicable(symbState: SymbState): Boolean = {
    symbState.ex match {
      case OperEx(TlaBoolOper.and, _*) => true
      case _                           => false
    }
  }

  override def apply(state: SymbState): SymbState = {
    simplifier.simplifyShallow(state.ex) match {
      case OperEx(TlaBoolOper.and, args @ _*) =>
        val finalState =
          if (args.isEmpty) {
            // empty conjunction is always true
            state.setRex(state.arena.cellTrue().toNameEx)
          } else {
            // use short-circuiting on state-level expressions (like in TLC)
            def toIte(es: Seq[TlaEx]): TlaEx = {
              es match {
                case Seq(last) =>
                  last

                case hd +: tail =>
                  tla
                    .ite(hd, toIte(tail), state.arena.cellFalse().toNameEx ? "b")
                    .typed(boolTypes, "b")
              }
            }

            // no lazy short-circuiting: simply translate if-then-else to a chain of if-then-else expressions
            val newState =
              if (rewriter.config.shortCircuit) {
                // create a chain of IF-THEN-ELSE expressions and rewrite them
                state.setRex(toIte(args))
              } else {
                // simply translate to a conjunction
                var nextState = state.updateArena(_.appendCell(BoolT()))
                val pred = nextState.arena.topCell.toNameEx

                def mapArg(argEx: TlaEx): TlaEx = {
                  nextState = rewriter.rewriteUntilDone(nextState.setRex(argEx))
                  nextState.ex
                }

                val rewrittenArgs = args map mapArg
                val eq = tla.eql(pred ? "b", tla.and(rewrittenArgs: _*) ? "b").typed(boolTypes, "b")
                rewriter.solverContext.assertGroundExpr(eq)
                nextState.setRex(pred)
              }
            rewriter.rewriteUntilDone(newState)
          }

        finalState

      case e @ _ =>
        // the simplifier has rewritten the conjunction to some other expression
        rewriter.rewriteUntilDone(state.setRex(e))
    }
  }

  private def lazyCircuit(state: SymbState, es: Seq[TlaEx]): SymbState = {
    val cellFalse = state.arena.cellFalse()
    if (es.isEmpty) {
      state.setRex(state.arena.cellTrue().toNameEx)
    } else {
      val (head, tail) = (es.head, es.tail)
      val headState = rewriter.rewriteUntilDone(state.setRex(head))
      val headCell = headState.asCell
      rewriter.solverContext.push()
      rewriter.solverContext.assertGroundExpr(headCell.toNameEx)
      val sat = rewriter.solverContext.sat()
      rewriter.solverContext.pop()
      if (!sat) {
        // always unsat, prune immediately
        headState.setRex(cellFalse.toNameEx)
      } else {
        val tailState = lazyCircuit(headState, tail)
        if (simplifier.isFalseConst(tailState.ex)) {
          // prune by propagating false
          tailState
        } else {
          // propagate
          var nextState = tailState.updateArena(_.appendCell(BoolT()))
          val pred = nextState.asCell.toNameEx
          val eq = tla
            .equiv(pred ? "b", tla.and(headCell.toNameEx ? "b", tailState.ex) ? "b")
            .typed(boolTypes, "b")
          rewriter.solverContext.assertGroundExpr(eq)
          nextState.setRex(pred)
        }
      }
    }
  }
}
