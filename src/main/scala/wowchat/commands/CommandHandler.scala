package wowchat.commands

import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.entities.MessageChannel
import wowchat.common.Global
import wowchat.game.{GamePackets, GameResources, GuildInfo, GuildMember}

import scala.collection.mutable
import scala.util.Try

case class WhoRequest(messageChannel: MessageChannel, playerName: String)
case class WhoResponse(playerName: String, guildName: String, lvl: Int, cls: String, race: String, gender: Option[String], zone: String)

object CommandHandler extends StrictLogging {

  private val NOT_ONLINE = "Bot is not online."

  // make some of these configurable
  private val trigger = "?"

  // gross. rewrite
  var whoRequest: WhoRequest = _

  private def handleGoldPicker(args: Array[String], messageChannel: MessageChannel): Unit = {
    val game = Global.game.getOrElse {
      messageChannel.sendMessage("No hay conexión al juego actualmente.").queue()
      return
    }

    if (args.isEmpty) {
      sendGoldPickerHelp(messageChannel)
      return
    }

    args(0).toLowerCase match {
      case "on" =>
        game.startGoldPicker()
        messageChannel.sendMessage("GoldPicker ha sido activado.").queue()
      case "off" =>
        game.stopGoldPicker()
        messageChannel.sendMessage("GoldPicker ha sido desactivado.").queue()
      case "status" =>
        messageChannel.sendMessage(game.getGoldPickerStatus).queue()
      case "msg" =>
        if (args.length < 2) {
          messageChannel.sendMessage("Por favor, proporciona un mensaje.").queue()
        } else {
          val newMessage = args.tail.mkString(" ")
          game.setGoldPickerMessage(newMessage)
          messageChannel.sendMessage(s"Mensaje de GoldPicker actualizado a: $newMessage").queue()
        }
      case "mindelay" =>
        if (args.length < 2 || !args(1).forall(_.isDigit) || args(1).toInt < 10) {
          messageChannel.sendMessage("Por favor, proporciona un número válido para el delay mínimo. Valor mínimo 10s.").queue()
        } else {
          val newDelay = args(1).toInt
          game.setGoldPickerMinDelay(newDelay)
          messageChannel.sendMessage(s"Delay mínimo de GoldPicker actualizado a $newDelay segundos.").queue()
        }
      case "maxdelay" =>
        if (args.length < 2 || !args(1).forall(_.isDigit) || args(1).toInt < game.getGoldPickerMinDelay()) {
          messageChannel.sendMessage("Por favor, proporciona un número válido para el delay máximo.").queue()
        } else {
          val newDelay = args(1).toInt
          game.setGoldPickerMaxDelay(newDelay)
          messageChannel.sendMessage(s"Delay máximo de GoldPicker actualizado a $newDelay segundos.").queue()
        }
      case "help" | _ =>
        sendGoldPickerHelp(messageChannel)
    }
  }

  private def sendGoldPickerHelp(messageChannel: MessageChannel): Unit = {
    val helpMessage = """
                        |Uso del comando GoldPicker:
                        |?goldpicker on - Activa GoldPicker
                        |?goldpicker off - Desactiva GoldPicker
                        |?goldpicker status - Muestra el estado actual de GoldPicker
                        |?goldpicker msg <mensaje> - Establece el mensaje de GoldPicker
                        |?goldpicker mindelay <num> - Establece el mínimo valor de delay en segundos
                        |?goldpicker maxdelay <num> - Establece el máximo valor de delay en segundos
                        |?goldpicker help - Muestra este mensaje de ayuda
    """.stripMargin
    messageChannel.sendMessage(helpMessage).queue()
  }

  private def handleAutoFlood(args: Array[String], messageChannel: MessageChannel): Unit = {
    val game = Global.game.getOrElse {
      messageChannel.sendMessage("No hay conexión al juego actualmente.").queue()
      return
    }

    if (args.isEmpty) {
      sendAutoFloodHelp(messageChannel)
      return
    }

    args(0).toLowerCase match {
      case "on" =>
        game.startAutoFlood()
        messageChannel.sendMessage("AutoFlood ha sido activado.").queue()
      case "off" =>
        game.stopAutoFlood()
        messageChannel.sendMessage("AutoFlood ha sido desactivado.").queue()
      case "status" =>
        messageChannel.sendMessage(game.getAutoFloodStatus).queue()
      case "msg" =>
        if (args.length < 2) {
          messageChannel.sendMessage("Por favor, proporciona un mensaje.").queue()
        } else {
          val newMessage = args.tail.mkString(" ")
          game.setAutoFloodMessage(newMessage)
          messageChannel.sendMessage(s"Mensaje de AutoFlood actualizado a: $newMessage").queue()
        }
      case "delay" =>
        if (args.length < 2 || !args(1).forall(_.isDigit) || args(1).toInt < 10) {
          messageChannel.sendMessage("Por favor, proporciona un número válido para el delay. Valor mínimo 10s.").queue()
        } else {
          val newDelay = args(1).toInt
          game.setAutoFloodDelay(newDelay)
          messageChannel.sendMessage(s"Delay de AutoFlood actualizado a $newDelay segundos.").queue()
        }
      case "channels" =>
        if (args.length < 2) {
          messageChannel.sendMessage("Por favor, proporciona al menos un canal.").queue()
        } else {
          val channels = args.tail.toSet
          game.setAutoFloodChannels(channels)
          messageChannel.sendMessage(s"Canales de AutoFlood actualizados a: ${channels.mkString(", ")}").queue()
        }
      case "help" | _ =>
        sendAutoFloodHelp(messageChannel)
    }
  }

  private def sendAutoFloodHelp(messageChannel: MessageChannel): Unit = {
    val helpMessage = """
                        |Uso del comando AutoFlood:
                        |?autoflood on - Activa AutoFlood
                        |?autoflood off - Desactiva AutoFlood
                        |?autoflood status - Muestra el estado actual de AutoFlood
                        |?autoflood msg <mensaje> - Establece el mensaje de AutoFlood
                        |?autoflood delay <num> - Establece el delay en segundos
                        |?autoflood channels <canales> - Establece los canales (ej: yell say 1 2)
                        |?autoflood help - Muestra este mensaje de ayuda
    """.stripMargin
    messageChannel.sendMessage(helpMessage).queue()
  }

  // returns back the message as an option if unhandled
  // needs to be refactored into a Map[String, <Intelligent Command Handler Function>]
  def apply(fromChannel: MessageChannel, message: String): Boolean = {
    if (!message.startsWith(trigger)) {
      return false
    }

    val splt = message.substring(trigger.length).split(" ")
    val possibleCommand = splt(0).toLowerCase
    val arguments = if (splt.length > 1 && splt(1).length <= 16) Some(splt(1)) else None
    val args = splt.slice(1, splt.length)

    logger.info(s"Command received: $message")

    Try {
      possibleCommand match {
        case "who" | "online" =>
          Global.game.fold({
            fromChannel.sendMessage(NOT_ONLINE).queue()
            return true
          })(game => {
            val whoSucceeded = game.handleWho(arguments)
            if (arguments.isDefined) {
              whoRequest = WhoRequest(fromChannel, arguments.get)
            }
            whoSucceeded
          })
        case "armory" =>
          Global.game.fold({
            fromChannel.sendMessage(NOT_ONLINE).queue()
            return true
          })(game => {
            val armorySucceeded = game.handleArmory()
            armorySucceeded
          })
        case "gmotd" =>
          Global.game.fold({
            fromChannel.sendMessage(NOT_ONLINE).queue()
            return true
          })(_.handleGmotd())
        case "goldpicker" =>
          Global.game.fold({
            fromChannel.sendMessage(NOT_ONLINE).queue()
            return true
          })(game => {
            handleGoldPicker(args, fromChannel)
            None
          })
        case "autoflood" =>
          Global.game.fold({
            fromChannel.sendMessage(NOT_ONLINE).queue()
            return true
          })(game => {
            handleAutoFlood(args, fromChannel)
            None
          })
        case _ => Some(message)
      }
    }.fold(throwable => {
      logger.error(s"Error handling command: $message")
      // command not found, should send to wow chat
      false
    }, opt => {
      // command found, do not send to wow chat
      if (opt.isDefined) {
        fromChannel.sendMessage(opt.get).queue()
      }
      true
    })
  }

  // eww
  def handleWhoResponse(whoResponse: Option[WhoResponse],
                        guildInfo: GuildInfo,
                        guildRoster: mutable.Map[Long, GuildMember],
                        guildRosterMatcherFunc: GuildMember => Boolean): Iterable[String] = {
    whoResponse.map(r => {
      Seq(s"${r.playerName} ${if (r.guildName.nonEmpty) s"<${r.guildName}> " else ""}is a level ${r.lvl}${r.gender.fold(" ")(g => s" $g ")}${r.race} ${r.cls} currently in ${r.zone}.")
    }).getOrElse({
      // Check guild roster
      guildRoster
        .values
        .filter(guildRosterMatcherFunc)
        .map(guildMember => {
          val cls = new GamePackets{}.Classes.valueOf(guildMember.charClass) // ... should really move that out
          val days = guildMember.lastLogoff.toInt
          val hours = ((guildMember.lastLogoff * 24) % 24).toInt
          val minutes = ((guildMember.lastLogoff * 24 * 60) % 60).toInt
          val minutesStr = s" $minutes minute${if (minutes != 1) "s" else ""}"
          val hoursStr = if (hours > 0) s" $hours hour${if (hours != 1) "s" else ""}," else ""
          val daysStr = if (days > 0) s" $days day${if (days != 1) "s" else ""}," else ""

          val guildNameStr = if (guildInfo != null) {
            s" <${guildInfo.name}>"
          } else {
            // Welp, some servers don't set guild guid in character selection packet.
            // The only other way to get this information is through parsing SMSG_UPDATE_OBJECT
            // and its compressed version which is quite annoying especially across expansions.
            ""
          }

          s"${guildMember.name}$guildNameStr is a level ${guildMember.level} $cls currently offline. " +
            s"Last seen$daysStr$hoursStr$minutesStr ago in ${GameResources.AREA.getOrElse(guildMember.zoneId, "Unknown Zone")}."
        })
    })
  }
}
