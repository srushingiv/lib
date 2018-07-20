package org.skrushingiv.actor.entity

import akka.actor._
import akka.dispatch.ControlMessage
import akka.pattern._
import akka.util.Timeout
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success}
import EntityCoordinator._

/**
  * Coordinates sending messages to child "entity" actors which may, or may not, be currently instantiated. Creates
  * new children as needed using the supplied delegate.
  *
  * This is similar to Akka's existing "Cluster Sharding" for entities with the primary difference being that it makes
  * no attempt to dictate the host within which child actors will be created. In theory, the supplied delegate
  * could be configured with customized Deploy settings for child props that direct the actor system to create the
  * new children on specific cluster nodes (although, this is currently untested).
  *
  * @param delegate An object that knows how to create child actor Props when supplied with an ID
  * @tparam Id The identifier type (e.g.: UUID or String)
  */
final class EntityCoordinator[Id: ClassTag](delegate: EntityCoordinatorDelegate[Id]) extends Actor with ActorLogging {
  import delegate._

  private var entities = Set.empty[Id]
  private var releasing = Set.empty[Id]
  private var queue = Vector.empty[(EntityMessage[Id], ActorRef)]

  private def release(id: Id) = if (entities(id)) {
    entities -= id
    context child childNameForId(id) foreach { c ⇒
      c ! PoisonPill
      releasing += id
    }
  }

  private def dequeue(id: Id) = {
    val b = Vector.newBuilder[(EntityMessage[Id], ActorRef)]
    for (entry@(msg, sender) ← queue) {
      if (msg.id == id) self.tell(msg, sender)
      else b += entry
    }
    queue = b.result
  }

  override def receive: Receive = {

    case EntityTerminated(id: Id) ⇒
      entities -= id
      releasing -= id
      dequeue(id)
      log.debug("Coordinated entity terminated: {}", childNameForId(id))

    case ReleaseEntity(id: Id) ⇒
      log.debug("Releasing coordinated entity: {}", childNameForId(id))
      release(id)

    case em@ EntityMessage(id: Id, msg, materialize) ⇒
      if (entities(id)) {
        if (msg == PoisonPill) release(id) // catch attempts to stop coordinated entities
        else context child childNameForId(id) foreach (_ forward msg)
      } else if (materialize) {
        if (releasing(id)) {
          queue :+= (em.asInstanceOf[EntityMessage[Id]] → sender()) // catch messages to exiting entities
        }
        else if (msg != PoisonPill) {
          log.debug("Materializing coordinated entity: {}", childNameForId(id))
          val e = context.actorOf(materializeChild(id), childNameForId(id))
          entities += id
          context.watchWith(e, EntityTerminated(id))
          e forward msg
        }
      }
      else if (msg != PoisonPill) onUndelivered(id, msg)

  }
}

object EntityCoordinator {

  // mimic the actor DSL but with encapsulated wrapping of messages to EntityMessage
  class EntityCoordinatorDSL[Id](recipient: ActorRef, wrap: (Any) ⇒ EntityMessage[Id]) {
    def forward(msg: Any)(implicit context: ActorContext): Unit = recipient forward wrap(msg)

    def !(msg: Any)(implicit sender: ActorRef = Actor.noSender): Unit = recipient ! wrap(msg)

    @inline def tell(msg: Any, sender: ActorRef): Unit = this.!(msg)(sender)

    def ?(msg: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[Any] =
      new AskableActorRef(recipient) ? wrap(msg)

    @inline def ask(msg: Any)(implicit timeout: Timeout, sender: ActorRef = Actor.noSender): Future[Any] = this.?(msg)

    def |[T](future: Future[T])(implicit ec: ExecutionContext, sender: ActorRef = Actor.noSender): Future[T] =
      future andThen {
        case Success(r) ⇒ recipient ! wrap(r)
        case Failure(f) ⇒ recipient ! wrap(Status.Failure(f))
      }

    // alternate right-associative "pipeTo" syntax
    @inline def |:[T](future: Future[T])(implicit ec: ExecutionContext, sender: ActorRef = Actor.noSender): Future[T] =
      this.|(future)
  }

  case class EntityMessage[Id](id: Id, msg: Any, materialize: Boolean = true)

  private case class EntityTerminated[Id](id: Id) extends ControlMessage

  // a coordinated entity can send this message to it's parent (the coordinator) to signal that it wants to stop.
  // This allows the coordinator to intercept any inbound messages between the time it receives this message
  // and when it is notified of the child's termination.
  case class ReleaseEntity[Id](id: Id)

  def apply[Id: ClassTag](name: String, delegate: EntityCoordinatorDelegate[Id])(implicit arf: ActorRefFactory): EntityCoordinatorRef[Id] =
    new EntityCoordinatorRef[Id](arf.actorOf(Props(new EntityCoordinator(delegate)), name))
}

class EntityCoordinatorRef[Id](ref: ActorRef) extends Serializable {
  @inline def ~>(id: Id): EntityCoordinatorDSL[Id] = apply(id)

  @inline def ?>(id: Id): EntityCoordinatorDSL[Id] = apply(id, materialize = false)

  def apply(id: Id, materialize: Boolean = true): EntityCoordinatorDSL[Id] =
    new EntityCoordinatorDSL(ref, EntityMessage(id, _, materialize))

  def release(id: Id): Unit = ref ! ReleaseEntity(id)
}

trait EntityCoordinatorDelegate[Id] {
  def childNameForId(id: Id): String

  def materializeChild(id: Id): Props

  def onUndelivered(id: Id, msg: Any): Unit = ()
}
