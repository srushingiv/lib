package org.skrushingiv.util

import org.skrushingiv.util.Composition.MonadTraverser

import scala.concurrent.{ExecutionContext, Future}
import scala.language.{higherKinds, implicitConversions}
import scalaz._

/**
  * A monad for simplifying traversal and composition of Monadic types (Collections, Options, etc).
  *
  * This monadic wrapper enables 1-tier for-comprehensions for any compliant monad-of-monad type composition.
  */
class Composition[Outer[_], Inner[_], A](val composed: Outer[Inner[A]])
                                        (implicit om: Monad[Outer], im: MonadPlus[Inner]) {
  def foreach[U](f: A => U)(implicit ot: Outer[Inner[A]] => MonadTraverser[Inner[A]],
                            it: Inner[A] => MonadTraverser[A]): Unit =
    composed foreach { _ foreach f }

  def map[B](fn: A => B): Composition[Outer, Inner, B] =
    new Composition[Outer, Inner, B](Functor[Outer].compose[Inner].apply(composed)(fn))

  /**
    * Discards organization in the existing outer monad and replaces it with the product of mapping each inner monad's elements
    * through the provided function. The result will contain the inner monad type of the mapped-to function result.
    */
  def flatMap[Inner0[_], B](fn: A => Composition[Outer, Inner0, B])
                (implicit foldable: Foldable[Inner],
                 im0: MonadPlus[Inner0],
                 m: Monoid[Outer[Inner0[B]]] // scalaz should compose this for free
                ): Composition[Outer, Inner0, B] = {
    def doMap(inner: Inner[A]) = foldable.fold(im.map(inner)(fn.andThen(_.composed)))
    new Composition[Outer, Inner0, B](om join om(composed)(doMap))
  }

  /** Filters the contents of the inner monads. No attempt is made to collapse or remove empty containing monads. */
  def filter(pred: A => Boolean): Composition[Outer, Inner, A] =
    new Composition[Outer, Inner, A](om.map(composed){ im.filter(_)(pred) })

  /** Used by for-comprehensions. */
  def withFilter(pred: A => Boolean): Composition[Outer, Inner, A] = filter(pred)
}

object Composition
{
  trait MonadTraverser[A] extends Traversable[A]

  object MonadTraverser {
    /** Does not guarantee the execution order of callback functions. */
    implicit def nonblockingFutureTraverser[A](f: Future[A])(implicit ec: ExecutionContext): MonadTraverser[A] =
      new MonadTraverser[A] { override def foreach[U](fn: A => U): Unit = f foreach fn }


    implicit def fromTraversableOnce[M[_], A](m: M[A])(implicit ev: M[A] => Traversable[A]): MonadTraverser[A] =
      new MonadTraverser[A] { override def foreach[U](fn: A => U): Unit = ev(m) foreach fn }
  }

  // alias for apply
  def lift[O[_]: Monad, I[_]: MonadPlus, A](c: O[I[A]]): Composition[O, I, A] = apply[O, I, A](c)

  def apply[O[_]: Monad, I[_]: MonadPlus, A](c: O[I[A]]): Composition[O, I, A] = new Composition[O, I, A](c)

  def unapply[O[_], I[_], A](c: Composition[O, I, A]): Option[O[I[A]]] = Some(c.composed)

  implicit def toComposed[O[_], I[_], A](c: Composition[O, I, A]): O[I[A]] = c.composed

}
