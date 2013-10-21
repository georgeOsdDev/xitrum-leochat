package leochat

import xitrum.Server
import leochat.action.MsgQManager

object Boot {
  def main(args: Array[String]) {
    MsgQManager.start()
    Server.start()
  }
}
