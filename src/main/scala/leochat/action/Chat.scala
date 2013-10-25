package leochat.action

import akka.actor.{Actor, ActorRef, Props}
import glokka.Registry
import xitrum.{Action, Config, Logger, WebSocketActor, WebSocketBinary, WebSocketPing, WebSocketPong, WebSocketText}
import xitrum.util.Json
import xitrum.annotation.{GET, WEBSOCKET}
import leochat.model.{LeoFS, Msg}
import xitrum.annotation.CacheActionDay
import akka.actor.Terminated

object MsgQManager {
  val MAX_LATEST_MSGS = 10
  val MAX_OLDER_MSGS = 10

  val NAME = {
    val leofsConfig = xitrum.Config.application.getConfig("leofs")
    leofsConfig.getString("bucket")
  }

  val registry = Registry.start(Config.actorSystem, "proxy")

  // Dummy method to force the start of the registry;
  // Call this method at program start
  def start() {}
}

case class MsgsFromQueue(msgs: Seq[Msg])
case class Publish(msg: String, name: String)
case object Subscribe
case object Recovery

class MsgQManager extends Actor with Logger {
  import MsgQManager.MAX_LATEST_MSGS
  private var clients    = Seq[ActorRef]()
  private var latestMsgs = LeoFS.readHead(MAX_LATEST_MSGS).reverse

  def receive = {
    case Publish(msg, name) =>
      val saved = LeoFS.save(msg, name)
      saved match {
        case Some(msg) =>
          latestMsgs = (latestMsgs :+ msg).takeRight(MAX_LATEST_MSGS)
          clients.foreach(_ ! MsgsFromQueue(Seq(msg)))
        case None =>
      }

    case Subscribe =>
      logger.debug("client join :" + sender.path.name)
      clients = clients :+ sender
      logger.debug("clients count :" + clients.length)
      sender ! MsgsFromQueue(latestMsgs)
      context.watch(sender)

    case Terminated(client) =>
      logger.debug("client terminated :" + sender.path.name)
      clients = clients.filterNot(_ == client)
      logger.debug("clients count :" + clients.length)

    case Recovery =>
      logger.debug("client join :" + sender.path.name)
      clients = clients :+ sender
      logger.debug("clients count :" + clients.length)
      context.watch(sender)

    case unexpected =>
      logger.warn("Unexpected message: " + unexpected)

  }
}


@GET("")
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
    getMsgQManager(true)
  }

  private def getMsgQManager(isInit: Boolean) {
    val registry = MsgQManager.registry

    registry ! Registry.LookupOrCreate(MsgQManager.NAME)
    context.become {
      case Registry.LookupResultOk(_, actorRef) =>
        chatStart(actorRef, isInit)

      case Registry.LookupResultNone(_) =>
        val tmp = Config.actorSystem.actorOf(Props[MsgQManager])
        registry ! Registry.Register(MsgQManager.NAME, tmp)
        context.become {
          case Registry.RegisterResultOk(_, actorRef) =>
            chatStart(actorRef, isInit)

          case Registry.RegisterResultConflict(_, actorRef) =>
            Config.actorSystem.stop(tmp)
            chatStart(actorRef, isInit)
        }

      case unexpected =>
        logger.warn("Unexpected message: " + unexpected)
    }
  }

  private def chatStart(actorRef: ActorRef, isInit: Boolean) {
    msgQueManager = actorRef
    context.watch(msgQueManager)
    val initMsg = if(isInit) Subscribe else Recovery
    msgQueManager ! initMsg
    context.become {
      case MsgsFromQueue(msgs) =>
        msgs.foreach { msg =>
          respondWebSocketText(Json.generate(msg))
        }

      case WebSocketText(text) =>
        msgQueManager ! Publish(text, self.path.name)

      case Terminated(msgQueManager) =>
        Thread.sleep(1000L * (scala.util.Random.nextInt(3)+1))
        getMsgQManager(false)

      case unexpected =>
        logger.warn("Unexpected message: " + unexpected)
    }
  }
}
