package leochat.action

import akka.actor.{Actor, ActorRef, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import xitrum.{Config, Logger, SockJsActor, SockJsText, WebSocketActor, WebSocketText, WebSocketBinary, WebSocketPing, WebSocketPong}
import xitrum.annotation.{GET, SOCKJS, WEBSOCKET}
import xitrum.mq.{MessageQueue, QueueMessage}
import leochat.util.LeoFS
import glokka.Registry

object MessageQueManager{

  val msgQueManager = Config.actorSystem.actorOf(Props[MessageQueManager], "msgQueManager")
  def getMsgManager:ActorRef ={
    msgQueManager
  }
  val registry = Registry.start(Config.actorSystem, "proxy")
  def getRegistry:ActorRef ={
    registry
  }
  msgQueManager ! "init"
  registry ! Registry.Register(Config.xitrum.port.toString, msgQueManager)

//  val clusterListener = Config.actorSystem.actorOf(Props[SimpleClusterListener], name = "clusterListener")
//  Cluster(Config.actorSystem).subscribe(clusterListener, classOf[ClusterDomainEvent])
}

//class SimpleClusterListener extends Actor {
//  var managers = Seq[ActorRef]()
//  def receive = {
//    case state: CurrentClusterState ⇒
//    case MemberUp(member) ⇒
//      member.address.port
//      managers = managers :+ sender
//      managers.foreach{manager =>
//        manager ! member
//      }
//    case UnreachableMember(member) ⇒
//    case MemberRemoved(member, previousStatus) ⇒
//    case _: ClusterDomainEvent ⇒ // ignore
//  }
//}

case class MsgsFromQueue(msgs: Seq[String])
case class Publish(msg:String);
case class Subscribe(num:Int);
case class NewManager(name:String,actorRef:ActorRef);

class MessageQueManager extends Actor with Logger{
  var clients = Seq[ActorRef]()
  var managers = Seq[ActorRef]()
  val registry = MessageQueManager.getRegistry

  def receive = {
    case pub: Publish =>
      // s3.listObjects() they are returned in alphabetical order
      val key = (- System.currentTimeMillis()).toString
      LeoFS.save(key,pub.msg)
      clients.foreach{client =>
        client ! MsgsFromQueue(Seq(pub.msg))
      }
      managers.foreach{manager =>
        manager ! MsgsFromQueue(Seq(pub.msg))
      }
    case sub: Subscribe =>
        clients = clients :+ sender
        val messages = LeoFS.readHead(sub.num)
        sender ! MsgsFromQueue(messages)
    case msg : MsgsFromQueue => //manager to manager
      clients.foreach{client =>
        client ! msg
      }
    // TODO : who trigger this message?
    case newManager: NewManager =>
      managers = managers :+ newManager.actorRef
    case _ =>
      logger.error("unexpected message")
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
  val msgQueManager = MessageQueManager.getMsgManager
  def execute() {

    msgQueManager ! Subscribe(10) // read latest 10
    context.become {
      case MsgsFromQueue(msgs) =>
        msgs.foreach { msg => respondWebSocketText(msg) }
      case WebSocketText(text) =>
        msgQueManager ! Publish(text)
      case WebSocketBinary(bytes) =>
      case WebSocketPing =>
      case WebSocketPong =>
    }
  }
}