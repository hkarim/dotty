package dotty.tools.dotc
package transform

import core.Contexts.Context
import core.NameKinds._
import core.Symbols._
import core.Flags._
import core.Decorators._
import MegaPhase.MiniPhase
import ast.Trees._
import util.Property

/** Add accessors for all protected accesses. An accessor is needed if
 *  according to the rules of the JVM a protected class member is not accesissible
 *  from the point of access, but is accessible if the access is from an enclosing
 *  class. In this point a public access method is placed in that enclosing class.
 */
object ProtectedAccessors {
  val name = "protectedAccessors"

  private val LHS = new Property.StickyKey[Unit]

  /** Is the current context's owner inside the access boundary established by `sym`? */
  def insideBoundaryOf(sym: Symbol)(implicit ctx: Context): Boolean = {
    if (sym.is(JavaDefined)) {
      sym.is(JavaStatic) ||  // Java's static protected definitions are treated as public
      ctx.owner.enclosingPackageClass == sym.enclosingPackageClass
    }
    else {
      // For Scala-defined symbols we currently allow private and protected accesses
      // from inner packages, and compensate by widening accessibility of such symbols to public.
      // It would be good if we could revisit this at some point.
      val boundary = sym.accessBoundary(sym.enclosingPackageClass)
      ctx.owner.isContainedIn(boundary) || ctx.owner.isContainedIn(boundary.linkedClass)
    }
  }

  /** Do we need a protected accessor if the current context's owner
   *  is not in a subclass or subtrait of `sym`?
   */
  def needsAccessorIfNotInSubclass(sym: Symbol)(implicit ctx: Context): Boolean =
    sym.isTerm && sym.is(Protected) &&
    !sym.owner.is(Trait) && // trait methods need to be handled specially, are currently always public
    !insideBoundaryOf(sym)

   /** Do we need a protected accessor for accessing sym from the current context's owner? */
   def needsAccessor(sym: Symbol)(implicit ctx: Context): Boolean =
    needsAccessorIfNotInSubclass(sym) &&
    !ctx.owner.enclosingClass.derivesFrom(sym.owner)
}

class ProtectedAccessors extends MiniPhase {
  import ast.tpd._
  import ProtectedAccessors._

  override def phaseName = ProtectedAccessors.name

  object Accessors extends AccessProxies {
    def getterName = ProtectedGetterName
    def setterName = ProtectedSetterName

    val insert = new Insert {
      def needsAccessor(sym: Symbol)(implicit ctx: Context) = ProtectedAccessors.needsAccessor(sym)
    }
  }

  override def prepareForAssign(tree: Assign)(implicit ctx: Context) = {
    tree.lhs match {
      case lhs: RefTree if needsAccessor(lhs.symbol) => lhs.putAttachment(LHS, ())
      case _ =>
    }
    ctx
  }

  private def isLHS(tree: RefTree) = tree.removeAttachment(LHS).isDefined

  override def transformIdent(tree: Ident)(implicit ctx: Context): Tree =
    if (isLHS(tree)) tree else Accessors.insert.accessorIfNeeded(tree)

  override def transformSelect(tree: Select)(implicit ctx: Context): Tree =
    if (isLHS(tree)) tree else Accessors.insert.accessorIfNeeded(tree)

  override def transformAssign(tree: Assign)(implicit ctx: Context): Tree =
    Accessors.insert.accessorIfNeeded(tree)

  override def transformTemplate(tree: Template)(implicit ctx: Context): Tree =
    cpy.Template(tree)(body = Accessors.addAccessorDefs(tree.symbol.owner, tree.body))
}