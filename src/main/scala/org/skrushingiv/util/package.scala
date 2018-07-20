package org.skrushingiv

import scalaz._

import scala.language.higherKinds

package object util {

  implicit class CompositionOps[O[_], I[_], A](val m: O[I[A]]) extends AnyVal {
    def liftC(implicit om: Monad[O], im: MonadPlus[I]): Composition[O, I, A] = Composition[O, I, A](m)
  }

}
