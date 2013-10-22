package leochat.action

import scala.util.parsing.json.JSONObject

import akka.actor.{Actor, ActorRef, Props}
import glokka.Registry
import xitrum.{Config, Logger, WebSocketActor, WebSocketBinary, WebSocketPing, WebSocketPong, WebSocketText}
import xitrum.annotation.{GET, WEBSOCKET}
import leochat.model.{LeoFS, Msg}


object MsgQManager {
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
case class Subscribe(num: Int)

class MsgQManager extends Actor with Logger {
  private var clients = Seq[ActorRef]()

  def receive = {
    case Publish(msg, name) =>
      val saved = LeoFS.save(msg, name).get
      saved match {
        case msg:Msg => clients.foreach(_ ! MsgsFromQueue(Seq(msg)))
        case ignore =>
      }

    case Subscribe(num) =>
      clients = clients :+ sender
      val msgs = LeoFS.readHead(num)
      sender ! MsgsFromQueue(msgs)

    case unexpected =>
      logger.warn("Unexpected message: " + unexpected)
  }
}

@GET("websocketImageChatDemo")
class LeoChat extends AppAction {
  def execute() {
    respondView()
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
    msgQueManager ! Subscribe(10)  // Read latest 10
    context.become {
      case MsgsFromQueue(msgs) =>
        msgs.foreach { msg =>
          val jsonObj = JSONObject(Map("key" -> msg.key, "date" -> msg.date, "name" -> msg.name, "body" -> msg.body));
          respondWebSocketText(jsonObj.toString())
        }

      case WebSocketText(text) =>
        msgQueManager ! Publish(text, self.path.name)

      case unexpected =>
        logger.warn("Unexpected message: " + unexpected)
    }
  }
}
