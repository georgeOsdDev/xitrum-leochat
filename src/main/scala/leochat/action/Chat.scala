package leochat.action

import akka.actor.{Actor, ActorRef, Props}
import glokka.Registry
import xitrum.{Action, Config, Logger, WebSocketActor, WebSocketBinary, WebSocketPing, WebSocketPong, WebSocketText}
import xitrum.util.Json
import xitrum.annotation.{GET, WEBSOCKET}
import leochat.model.{LeoFS, Msg}
import xitrum.annotation.CacheActionDay

object MsgQManager {
  val MAX_LATEST_MSGS = 10
  val MAX_OLDER_MSGS = 10

  val NAME = {
    val leofsConfig = xitrum.Config.application.getConfig("leofs")
    leofsConfig.getString("bucket")
  }

  val registry = Registry.start(Config.actorSystem, "proxy")

  // Dummy method to force the start of the registy;
  // Call this method at program start
  def start() {}
}

case class MsgsFromQueue(msgs: Seq[Msg])
case class Publish(msg: String, name: String)
case object Subscribe

class MsgQManager extends Actor with Logger {

  private var clients    = Seq[ActorRef]()
  private var latestMsgs = LeoFS.readHead(MsgQManager.MAX_LATEST_MSGS)

  def receive = {
    case Publish(msg, name) =>
      val saved = LeoFS.save(msg, name).get
      saved match {
        case msg: Msg =>
          latestMsgs = (latestMsgs :+ msg).tail
          clients.foreach(_ ! MsgsFromQueue(Seq(msg)))

        case ignore =>
      }

    case Subscribe =>
      clients = clients :+ sender
      sender ! MsgsFromQueue(latestMsgs)

    case unexpected =>
      logger.warn("Unexpected message: " + unexpected)
  }
}


@GET("leochat")
class LeoChat extends AppAction {
  def execute() {
    respondView()
  }
}

@GET("leochatRest")
@CacheActionDay(1)
class LeoChatRest extends Action {
  def execute() {
    val lastKey = paramo("lastKey").getOrElse("")
    respondJson(LeoFS.readWithMarker(lastKey, MsgQManager.MAX_OLDER_MSGS))
  }
}

@WEBSOCKET("leochat")
class LeoChatActor extends WebSocketActor{
  private var msgQueManager: ActorRef = _

  def execute() {
    getMsgQManager()
  }

  private def getMsgQManager() {
    val registry = MsgQManager.registry

    registry ! Registry.LookupOrCreate(MsgQManager.NAME)
    context.become {
      case Registry.LookupResultOk(_, actorRef) =>
        chatStart(actorRef)

      case Registry.LookupResultNone(_) =>
        val tmp = Config.actorSystem.actorOf(Props[MsgQManager], MsgQManager.NAME)
        registry ! Registry.Register(MsgQManager.NAME, tmp)
        context.become {
          case Registry.RegisterResultOk(_, actorRef) =>
            chatStart(actorRef)

          case Registry.RegisterResultConflict(_, actorRef) =>
            Config.actorSystem.stop(tmp)
            chatStart(actorRef)
        }

      case unexpected =>
        logger.warn("Unexpected message: " + unexpected)
    }
  }

  private def chatStart(actorRef: ActorRef) {
    msgQueManager = actorRef
    msgQueManager ! Subscribe
    context.become {
      case MsgsFromQueue(msgs) =>
        msgs.foreach { msg =>
          respondWebSocketText(Json.generate(msg))
        }

      case WebSocketText(text) =>
        msgQueManager ! Publish(text, self.path.name)

      case unexpected =>
        logger.warn("Unexpected message: " + unexpected)
    }
  }
}
