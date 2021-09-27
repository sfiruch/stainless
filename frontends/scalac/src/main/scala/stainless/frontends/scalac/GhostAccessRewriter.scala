/* Copyright 2009-2021 EPFL, Lausanne */

package stainless
package frontends.scalac

import extraction.xlang.{trees => xt}
import scala.tools.nsc._
import scala.tools.nsc.transform.Transform

import stainless.frontend.{CallBack, UnsupportedCodeException}

/** Extract each compilation unit and forward them to the Compiler callback */
trait GhostAccessRewriter extends Transform {
  import global._

  val pluginOptions: PluginOptions
  val phaseName = "ghost-removal"

  override def newTransformer(unit: global.CompilationUnit): Transformer = {
    if (pluginOptions.enableGhostElimination) {
      new GhostRewriteTransformer
    } else {
      new IdentityTransformer
    }
  }

  lazy val ghostAnnotation = rootMirror.getRequiredClass("stainless.annotation.ghost")

  private class IdentityTransformer extends Transformer {
    override def transform(tree: Tree): Tree = tree
  }

  private class GhostRewriteTransformer extends Transformer {

    /**
     * Is this symbol @ghost, or enclosed inside a ghost definition?
     *
     * Note: We exclude constructors from being ghost because we can't remove them anyway
     */
    private def effectivelyGhost(sym: Symbol): Boolean =
      (!sym.isConstructor &&
        sym.ownersIterator.exists(_.hasAnnotation(ghostAnnotation)))

    private def symbolIndex(tree: Tree): Int = tree match {
      case Apply(fun, args) => symbolIndex(fun) + 1
      case _ => 0
    }

    override def transform(tree: Tree): Tree = tree match {
      case Ident(_) if effectivelyGhost(tree.symbol) =>
        gen.mkZero(tree.tpe)

      case Select(_, _) if effectivelyGhost(tree.symbol) =>
        gen.mkZero(tree.tpe)

      case DefDef(mods, name, tparams, vparamss, tpt, rhs) if effectivelyGhost(tree.symbol) =>
        treeCopy.DefDef(tree, mods, name, tparams, vparamss, tpt, gen.mkZero(rhs.tpe))

      case ValDef(mods, name, tpt, rhs) if effectivelyGhost(tree.symbol) =>
        treeCopy.ValDef(tree, mods, name, tpt, gen.mkZero(rhs.tpe))

      // labels are generated by pattern matching but they are not real applications and should not
      // be touched. They are simple jumps and tampering with them may lead to runtime verification errors
      case Apply(fun, args) if tree.symbol.isLabel =>
        treeCopy.Apply(tree, fun, transformTrees(args))

      // This is similarly generated by pattern matching.
      // For instance, the pattern `(h: T, t: List[T]): Cons[T]` would match this case with:
      //  TypeTree = MethodType with params h: T, t: List[T] and result type Cons[T] (pattern matches are represented by a MethodType)
      //  args = (h @ _), (t @ _) (i.e. Bind trees)
      case Apply(tt@TypeTree(), args) =>
        treeCopy.Apply(tree, tt, transformTrees(args))

      case f @ Apply(fun, args) if effectivelyGhost(fun.symbol) =>
        gen.mkZero(tree.tpe)

      case f @ Apply(fun, args) =>
        val fun1 = super.transform(fun)
        val symParams0 = f.symbol.info.paramLists(symbolIndex(fun))

        // if the function has a repeated parameter the lengths of the two lists don't match
        // so we fill params up to the argument list length with the last parameter
        val symParams = if (symParams0.nonEmpty && definitions.isRepeated(symParams0.last))
          symParams0 ++ List.fill(args.length - symParams0.length)(symParams0.last)
        else
          symParams0

        val args1 = for ((param, arg) <- symParams.zip(args)) yield
          if (param.hasAnnotation(ghostAnnotation))
            gen.mkZero(param.tpe)
          else
            transform(arg)

        treeCopy.Apply(tree, fun1, args1)

      case Assign(lhs, rhs) =>
        if (effectivelyGhost(lhs.symbol))
          treeCopy.Assign(tree, lhs, gen.mkZero(rhs.tpe))
        else
          super.transform(tree)

      case _ => super.transform(tree)
    }
  }

}
