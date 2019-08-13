package at.forsyte.apalache.tla.lir.transformations.standard

import at.forsyte.apalache.tla.lir._
import at.forsyte.apalache.tla.lir.oper.{LetInOper, TlaFunOper}
import at.forsyte.apalache.tla.lir.transformations.{TlaExTransformation, TransformationTracker}
import at.forsyte.apalache.tla.lir.values.TlaStr

object SimplifyRecordAccess {
  private def simplifyLeaf( tracker : TransformationTracker ) : TlaExTransformation = tracker.track {
    case OperEx(
    TlaFunOper.app,
    OperEx( TlaFunOper.enum, args@_* ),
    ValEx( TlaStr( s ) )
    ) =>
      val recFields = ( args.grouped( 2 ).toSeq map {
        case Seq( ValEx( TlaStr( k ) ), v ) => k -> v
      } ).toMap

      recFields.getOrElse(
        s,
        throw new IllegalArgumentException( "Attempting to access nonexistent record field" )
      )
    case ex => ex
  }

  def apply( tracker : TransformationTracker ) : TlaExTransformation = tracker.track { ex =>
    val tr = simplifyLeaf( tracker )
    lazy val self = apply( tracker )
    ex match {
      case OperEx( op : LetInOper, body ) =>
        // Inline bodies of all op.defs
        val inlinedOperDecls = op.defs.map { x =>
          x.copy(
            body = self( x.body )
          )
        }

        val newOp = new LetInOper( inlinedOperDecls )
        val newBody = self( body )
        val retEx = if ( op == newOp && body == newBody ) ex else OperEx( newOp, newBody )

        tr( retEx )
      case ex@OperEx( op, args@_* ) =>
        val newArgs = args map self
        val retEx = if ( args == newArgs ) ex else OperEx( op, newArgs : _* )
        tr( retEx )
      case _ => tr( ex )
    }
  }
}
