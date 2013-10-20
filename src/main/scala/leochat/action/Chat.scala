package leochat.action

import akka.actor.{Actor, ActorRef, Props}
import xitrum.{Config, Logger, SockJsActor, SockJsText, WebSocketActor, WebSocketText, WebSocketBinary, WebSocketPing, WebSocketPong}
import xitrum.annotation.{GET, SOCKJS, WEBSOCKET}
import xitrum.mq.{MessageQueue, QueueMessage}
import leochat.util.LeoFS

@GET("websocketImageChatDemo")
class LeoChat extends AppAction {
  def execute() {
    respondView()
  }
}

case class MsgsFromQueue(msgs: Seq[String])
case class Publish(msg:String);
case class Subscribe(num:Int);

@WEBSOCKET("leochat")
class LeoChatActor extends WebSocketActor{
  val manager = MessageQueManager.getManager
  def execute() {

    manager ! Subscribe(10) // read latest 10
    context.become {
      case MsgsFromQueue(msgs) =>
        msgs.foreach { msg => respondWebSocketText(msg) }
      case WebSocketText(text) =>
        manager ! Publish(text)
      case WebSocketBinary(bytes) =>
      case WebSocketPing =>
      case WebSocketPong =>
    }
  }
}

object MessageQueManager{
  val manager = Config.actorSystem.actorOf(Props[MessageQueManager], "manager")
  def getManager:ActorRef ={
    manager
  }
}

class MessageQueManager extends Actor with Logger{
  val infinitie = "9999999999999".toLong
  var clients = Seq[ActorRef]()
  def receive = {
    case pub: Publish =>
      // s3.listObjects()) they are returned in alphabetical order
      val key = (infinitie - System.currentTimeMillis()).toString
      LeoFS.save(key,pub.msg)
      clients.foreach(_ ! MsgsFromQueue(Seq(pub.msg)))
    case sub: Subscribe =>
        clients = clients :+ sender
        val messages = LeoFS.readHead(sub.num)
        sender ! MsgsFromQueue(messages)
    case _ =>
      logger.error("unexpected message")
  }
}
