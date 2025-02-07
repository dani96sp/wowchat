package wowchat.game

trait AutoFloodHandler {
  def startAutoFlood(): Unit
  def stopAutoFlood(): Unit
  def setAutoFloodMessage(message: String): Unit
  def setAutoFloodDelay(delay: Int): Unit
  def setAutoFloodChannels(channels: Set[String]): Unit
  def getAutoFloodStatus: String
  def isAutoFloodActive: Boolean
} 