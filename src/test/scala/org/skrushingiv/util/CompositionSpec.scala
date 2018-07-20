package org.skrushingiv.util

import org.scalatest._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random
import scalaz.std.AllInstances._

class CompositionSpec extends AsyncWordSpec with Matchers {
  "A Composition" should {
    "compose with for-comprehensions" when {
      "created from future collections and filtered" in {
        val e = for {
          x <- Future.successful(List(1,2,3)).liftC
          if x != 2
          y <- Future.successful(Vector(0.1, 0.2, 0.3)).liftC
          if math.floor((x + y) * 10) % 11 == 0
        } yield x + y
        e.composed map { _ shouldBe Vector(1.1, 3.3) }
      }

      "created from future collections with exception" in {
        val ex = new RuntimeException("test")
        val e = for {
          x <- Future.successful(List(1,2,3)).liftC
          y <- Future.failed[Vector[Int]](ex).liftC
        } yield x + y
        e.composed.failed map { _ shouldBe ex }
      }

      "created from options of collections and filtered" in {
        val e = for {
          x <- Option(Vector('c', 'b', 'a')).liftC
          if x != 'c'
          y <- Option(List("a", "b", "c")).liftC
          if y.charAt(0) != x
        } yield y + x
        e.composed shouldBe Some(List("ab", "cb", "ba", "ca"))
      }

      "created from future of option and filtered" in {
        val e = for {
          x <- Future.successful(Vector('a', 'b', 'c')).liftC
          if x != 'c'
          y <- Future.successful(Option("d")).liftC
        } yield y + x
        e.composed map {_ shouldBe Some("dadb")}
      }

      "created from future of option with results of each old-inner collection summed together" in {
        val e = for {
          x <- Future.successful(List(1, 3, 5, 7)).liftC
          y <- Future.successful(Option(-3)).liftC
        } yield y + x
        e.composed map {_ shouldBe Some(4)}
      }

      "created from collections of collections" in {
        val e = for {
          y <- Stream(Some(5), None, Some(6)).liftC
          x <- Stream(List(1, 2), List(3, 4)).liftC
        } yield y * x
        e.composed shouldBe Stream(List(5, 10), List(15, 20), List(6, 12), List(18, 24))
      }

      "traverse future results asynchronously" in {
        // This is an example showing how foreach comprehensions with futures will
        // complete the executed functions at different times.

        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        implicit val ec = ExecutionContext.fromExecutor(pool)
        val sb = new StringBuffer()
        val letters = Vector("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m")

        def slowStr(s: String): Future[Option[String]] = Future { Thread.sleep(Random.nextInt(150)); Some(s) }

        for {
          s <- Future.successful(letters).liftC
          c <- slowStr(s).liftC
        } sb.append(c)

        Thread.sleep(1000)
        pool.shutdown

        sb.toString shouldNot be("abcdefghijklm")
      }
    }
  }
}
