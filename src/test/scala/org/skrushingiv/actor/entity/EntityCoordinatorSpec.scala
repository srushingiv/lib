package org.skrushingiv.actor.entity

import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, DeadLetter, Props}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class EntityCoordinatorSpec extends TestKit(ActorSystem("EntityCoordinatorSpec")) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  import EntityCoordinatorSpec._

  override def beforeAll(): Unit = {
    val deadLetterMonitorActor =
      system.actorOf(Props(new DeadLetterMonitorActor), name = "deadlettermonitoractor")
    system.eventStream.subscribe(deadLetterMonitorActor, classOf[DeadLetter])
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  implicit val timeout = Timeout(500.millis)
  import system.dispatcher

  "An EntityCoordinator" should {
    "materialize new children as appropriate" in {
      val coord = EntityCoordinator("testCoord0", testDelegate)
      coord ~> 3 ! "foo"
      expectMsg(1.second, "child-3:init")
      expectMsg(1.second, "child-3:foo")
      coord ~> 3 ! "bar"
      expectMsg(1.second, "child-3:bar")

      // ask syntax
      Await.result(coord ~> 4 ? "baz", 1.second) shouldBe "child-4:baz"
      expectMsg(1.second, "child-4:init")

      coord ~> 3 ! "boo"
      expectMsg(1.second, "child-3:boo")
      coord ~> 4 ! "boo"
      expectMsg(1.second, "child-4:boo")
      expectNoMessage(1.second)
    }

    "not materialize children as appropriate" in {
      val coord = EntityCoordinator("testCoord1", testDelegate)
      coord ?> 7 ! "a"
      expectNoMessage(1.second)
      coord ~> 11 ! "b"
      expectMsg(1.second, "child-11:init")
      expectMsg(1.second, "child-11:b")
      coord ?> 11 ! "c"
      expectMsg(1.second, "child-11:c")
      coord ?> 15 ! "d"
      expectNoMessage(1.second)
    }

    "rematerialize children as appropriate" in {
      val coord = EntityCoordinator("testCoord2", testDelegate)
      coord ~> 21 ! "12"
      expectMsg(1.second, "child-21:init")
      expectMsg(1.second, "child-21:12")

      // example of pipeTo syntax
      Future.successful("abc") |: coord(21)
      expectMsg(1.second, "child-21:abc")


      // example of release command
      coord release 21
      expectMsg(1.second, "child-21:stop")

      coord ?> 21 ! "d"
      expectNoMessage(1.second)
      coord ?> 21 ! "e"
      expectNoMessage(1.second)

      coord ~> 21 | Future.failed[Any](new RuntimeException("test"))
      expectMsg(1.second, "child-21:init")
      expectMsg(1.second, "child-21:Failure(java.lang.RuntimeException: test)")

      coord release 21
      expectMsg(1.second, "child-21:stop")

      coord ~> 21 ! "f"
      expectMsg(1.second, "child-21:init")
      expectMsg(1.second, "child-21:f")
      expectNoMessage(1.second)
    }
  }

}

object EntityCoordinatorSpec {

  class ChildActor(target: ActorRef) extends Actor {
    override def preStart(): Unit = target ! self.path.name+":init"
    override def postStop(): Unit = target ! self.path.name+":stop"
    override def receive: Receive = {
      case message ⇒ sender() ! self.path.name+":"+message
    }
  }

  // intercept dead letters to validate that we are not allowing messages to get lost.
  // the downside to this is that since each test is asynchronous, the
  class DeadLetterMonitorActor(implicit target: ActorRef) extends Actor with ActorLogging {
    def receive: Receive = {
      case d: DeadLetter => target ! d
    }
  }

  def testDelegate(implicit target: ActorRef): EntityCoordinatorDelegate[Int] = new EntityCoordinatorDelegate[Int] {
    override def childNameForId(id: Int): String = "child-"+id

    override def materializeChild(id: Int): Props = Props(new ChildActor(target))
  }

}
