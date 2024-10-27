package wowchat.game

trait GoldPickerHandler {
  def startGoldPicker(): Unit
  def stopGoldPicker(): Unit
  def setGoldPickerMessage(message: String): Unit
  def getGoldPickerMessage(): String
  def setGoldPickerMinDelay(delay: Int): Unit
  def getGoldPickerMinDelay(): Int
  def setGoldPickerMaxDelay(delay: Int): Unit
  def getGoldPickerMaxDelay(): Int
  def getGoldPickerStatus: String
  def isGoldPickerActive: Boolean
}
