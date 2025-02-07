package wowchat.game

trait GameCommandHandler extends GoldPickerHandler with AutoFloodHandler {

  def sendMessageToWow(tp: Byte, message: String, target: Option[String])
  def sendNotification(message: String)

  def handleWho(arguments: Option[String]): Option[String]
  def handleArmory(): Option[String]
  def handleGmotd(): Option[String]
}
