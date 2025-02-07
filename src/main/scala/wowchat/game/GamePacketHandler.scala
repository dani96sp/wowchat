package wowchat.game

import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.{Executors, TimeUnit, ScheduledFuture}

import wowchat.common._
import wowchat.game.warden.{WardenHandler, WardenPackets}
import com.typesafe.scalalogging.StrictLogging
import io.netty.buffer.{ByteBuf, PooledByteBufAllocator}
import io.netty.channel.{ChannelFuture, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import wowchat.commands.{CommandHandler, WhoResponse}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random

case class Player(name: String, charClass: Byte)
case class GuildMember(name: String, isOnline: Boolean, charClass: Byte, level: Byte, zoneId: Int, lastLogoff: Float)
case class ChatMessage(guid: Long, tp: Byte, message: String, channel: Option[String] = None)
case class NameQueryMessage(guid: Long, name: String, charClass: Byte)
case class AuthChallengeMessage(sessionKey: Array[Byte], byteBuf: ByteBuf)
case class CharEnumMessage(name: String, guid: Long, race: Byte, guildGuid: Long)
case class GuildInfo(name: String, ranks: Map[Int, String])

class GamePacketHandler(realmId: Int, realmName: String, sessionKey: Array[Byte], gameEventCallback: CommonConnectionCallback)
  extends ChannelInboundHandlerAdapter with GameCommandHandler with GamePackets with GoldPickerHandler with AutoFloodHandler with StrictLogging {

  protected var lastTradeGoldAmount: Int = 0
  protected var lastTradePlayerGuid: Long = 0
  protected var lastTradePlayerName: Option[String] = None

  private var _isGoldPickerActive: Boolean = false

  private val random = new Random()
  override def isGoldPickerActive: Boolean = _isGoldPickerActive
  private var goldPickerFuture: Option[ScheduledFuture[_]] = None
  private var goldPickerMessage: String = "can you give me some gold for spells and riding pls im new here"
  private var goldPickerMinDelay: Int = 44
  private var goldPickerMaxDelay: Int = 131

  // AutoFlood variables
  private var _isAutoFloodActive: Boolean = false
  override def isAutoFloodActive: Boolean = _isAutoFloodActive
  private var autoFloodFuture: Option[ScheduledFuture[_]] = None
  private var autoFloodMessage: String = ""
  private var autoFloodDelay: Int = 60
  private var autoFloodChannels: Set[String] = Set.empty

  protected val addonInfo: Array[Byte] = Array(
    0x56, 0x01, 0x00, 0x00, 0x78, 0x9C, 0x75, 0xCC, 0xBD, 0x0E, 0xC2, 0x30, 0x0C, 0x04, 0xE0, 0xF2,
    0x1E, 0xBC, 0x0C, 0x61, 0x40, 0x95, 0xC8, 0x42, 0xC3, 0x8C, 0x4C, 0xE2, 0x22, 0x0B, 0xC7, 0xA9,
    0x8C, 0xCB, 0x4F, 0x9F, 0x1E, 0x16, 0x24, 0x06, 0x73, 0xEB, 0x77, 0x77, 0x81, 0x69, 0x59, 0x40,
    0xCB, 0x69, 0x33, 0x67, 0xA3, 0x26, 0xC7, 0xBE, 0x5B, 0xD5, 0xC7, 0x7A, 0xDF, 0x7D, 0x12, 0xBE,
    0x16, 0xC0, 0x8C, 0x71, 0x24, 0xE4, 0x12, 0x49, 0xA8, 0xC2, 0xE4, 0x95, 0x48, 0x0A, 0xC9, 0xC5,
    0x3D, 0xD8, 0xB6, 0x7A, 0x06, 0x4B, 0xF8, 0x34, 0x0F, 0x15, 0x46, 0x73, 0x67, 0xBB, 0x38, 0xCC,
    0x7A, 0xC7, 0x97, 0x8B, 0xBD, 0xDC, 0x26, 0xCC, 0xFE, 0x30, 0x42, 0xD6, 0xE6, 0xCA, 0x01, 0xA8,
    0xB8, 0x90, 0x80, 0x51, 0xFC, 0xB7, 0xA4, 0x50, 0x70, 0xB8, 0x12, 0xF3, 0x3F, 0x26, 0x41, 0xFD,
    0xB5, 0x37, 0x90, 0x19, 0x66, 0x8F
  ).map(_.toByte)

  protected var selfCharacterId: Option[Long] = None
  protected var languageId: Byte = _
  protected var inWorld: Boolean = false
  protected var guildGuid: Long = _
  protected var guildInfo: GuildInfo = _
  protected var guildMotd: Option[String] = None

  protected var ctx: Option[ChannelHandlerContext] = None
  protected val playerRoster = LRUMap.empty[Long, Player]
  protected val guildRoster = mutable.Map.empty[Long, GuildMember]
  protected var lastRequestedGuildRoster: Long = _
  protected val executorService = Executors.newSingleThreadScheduledExecutor
  protected val executorServiceShortTasks = Executors.newSingleThreadScheduledExecutor

  // cannot use multimap here because need deterministic order
  private val queuedChatMessages = new mutable.HashMap[Long, mutable.ListBuffer[ChatMessage]]
  private var wardenHandler: Option[WardenHandler] = None
  private var receivedCharEnum = false

  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
    executorService.shutdown()
    executorServiceShortTasks.shutdown()
    this.ctx = None
    gameEventCallback.disconnected
    Global.game = None
    if (inWorld) {
      Global.discord.sendMessageFromWow(None, "Disconnected from server!", ChatEvents.CHAT_MSG_SYSTEM, None)
    }
    super.channelInactive(ctx)
  }

  // Vanilla does not have a keep alive packet
  protected def runKeepAliveExecutor: Unit = {}

  private def runPingExecutor: Unit = {
    executorService.scheduleWithFixedDelay(new Runnable {
      var pingId = 0

      override def run(): Unit = {
        val latency = Random.nextInt(50) + 90

        val byteBuf = PooledByteBufAllocator.DEFAULT.buffer(8, 8)
        byteBuf.writeIntLE(pingId)
        byteBuf.writeIntLE(latency)

        ctx.get.writeAndFlush(Packet(CMSG_PING, byteBuf))
        pingId += 1
      }
    }, 30, 30, TimeUnit.SECONDS)
  }

  private def runGuildRosterExecutor: Unit = {
    executorService.scheduleWithFixedDelay(() => {
      // Enforce updating guild roster only once per minute
      if (System.currentTimeMillis - lastRequestedGuildRoster >= 60000) {
        updateGuildRoster
      }
    }, 61, 61, TimeUnit.SECONDS)
  }

  def buildGuildiesOnline: String = {
    val characterName = Global.config.wow.character

    guildRoster
      .valuesIterator
      .filter(guildMember => guildMember.isOnline && !guildMember.name.equalsIgnoreCase(characterName))
      .toSeq
      .sortBy(_.name)
      .map(m => {
        s"${m.name} (${m.level} ${Classes.valueOf(m.charClass)} in ${GameResources.AREA.getOrElse(m.zoneId, "Unknown Zone")})"
      })
      .mkString(getGuildiesOnlineMessage(false), ", ", "")
  }

  def buildGuildiesOnlineArmory: String = {
    val characterName = Global.config.wow.character

    guildRoster
      .valuesIterator
      .filter(guildMember => guildMember.isOnline && !guildMember.name.equalsIgnoreCase(characterName))
      .toSeq
      .sortBy(_.name)
      .map(m => {
        s"https://armory.warmane.com/character/${m.name}/Icecrown/summary"
      })
      .mkString(getGuildiesOnlineMessage(false), "\n", "")
  }

  def getGuildiesOnlineMessage(isStatus: Boolean): String = {
    val size = guildRoster.count(_._2.isOnline) - 1
    val guildies = s"guildie${if (size != 1) "s" else ""}"

    if (isStatus) {
      s"$size $guildies online"
    } else {
      if (size <= 0) {
        "Currently no guildies online."
      } else {
        s"Currently $size $guildies online:\n"
      }
    }
  }

  protected def updateGuildiesOnline: Unit = {
    Global.discord.changeGuildStatus(getGuildiesOnlineMessage(true))
  }

  protected def queryGuildName: Unit = {
    val out = PooledByteBufAllocator.DEFAULT.buffer(4, 4)
    out.writeIntLE(guildGuid.toInt)
    ctx.get.writeAndFlush(Packet(CMSG_GUILD_QUERY, out))
  }

  private def updateGuildRoster: Unit = {
    lastRequestedGuildRoster = System.currentTimeMillis
    ctx.get.writeAndFlush(buildGuildRosterPacket)
  }

  protected def buildGuildRosterPacket: Packet = {
    Packet(CMSG_GUILD_ROSTER)
  }

  def sendLogout: Option[ChannelFuture] = {
    ctx.flatMap(ctx => {
      if (ctx.channel.isActive) {
        Some(ctx.writeAndFlush(Packet(CMSG_LOGOUT_REQUEST)))
      } else {
        None
      }
    })
  }

  override def sendMessageToWow(tp: Byte, message: String, target: Option[String]): Unit = {
    ctx.fold(logger.error("Cannot send message! Not connected to WoW!"))(ctx => {
      ctx.writeAndFlush(buildChatMessage(tp, message.getBytes("UTF-8"), target.map(_.getBytes("UTF-8"))))
    })
  }

  protected def buildChatMessage(tp: Byte, utf8MessageBytes: Array[Byte], utf8TargetBytes: Option[Array[Byte]]): Packet = {
    val out = PooledByteBufAllocator.DEFAULT.buffer(128, 8192)
    out.writeIntLE(tp)
    out.writeIntLE(languageId)
    utf8TargetBytes.foreach(utf8TargetBytes => {
      out.writeBytes(utf8TargetBytes)
      out.writeByte(0)
    })
    out.writeBytes(utf8MessageBytes)
    out.writeByte(0)
    Packet(CMSG_MESSAGECHAT, out)
  }

  override def sendNotification(message: String): Unit = {
    sendMessageToWow(ChatEvents.CHAT_MSG_GUILD, message, None)
  }

  def sendNameQuery(guid: Long): Unit = {
    ctx.foreach(ctx => {
      val out = PooledByteBufAllocator.DEFAULT.buffer(8, 8)
      out.writeLongLE(guid)
      ctx.writeAndFlush(Packet(CMSG_NAME_QUERY, out))
    })
  }

  override def handleWho(arguments: Option[String]): Option[String] = {
    if (arguments.isDefined) {
      val byteBuf = buildWhoMessage(arguments.get)
      ctx.get.writeAndFlush(Packet(CMSG_WHO, byteBuf))
      None
    } else {
      Some(buildGuildiesOnline)
    }
  }
  override def handleArmory(): Option[String] = {
    Some(buildGuildiesOnlineArmory)
  }

  override def handleGmotd(): Option[String] = {
    guildMotd.map(guildMotd => {
      val guildNotificationConfig = Global.config.guildConfig.notificationConfigs("motd")
      guildNotificationConfig.format
        .replace("%time", Global.getTime)
        .replace("%user", "")
        .replace("%message", guildMotd)
    })
  }

  protected def buildWhoMessage(name: String): ByteBuf = {
    val byteBuf = PooledByteBufAllocator.DEFAULT.buffer(64, 64)
    byteBuf.writeIntLE(0)  // level min
    byteBuf.writeIntLE(100) // level max
    byteBuf.writeBytes(name.getBytes("UTF-8"))
    byteBuf.writeByte(0) // ?
    byteBuf.writeByte(0) // ?
    byteBuf.writeIntLE(0xFFFFFFFF) // race mask (all races)
    byteBuf.writeIntLE(0xFFFFFFFF) // class mask (all classes)
    byteBuf.writeIntLE(0) // zones count
    byteBuf.writeIntLE(0) // strings count
  }

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    logger.info("Connected! Authenticating...")
    this.ctx = Some(ctx)
    Global.game = Some(this)
    runPingExecutor
    runAutoSayExecutor
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: scala.Any): Unit = {
    msg match {
      case msg: Packet =>
        channelParse(msg)
        msg.byteBuf.release
      case msg => logger.error(s"Packet is instance of ${msg.getClass}")
    }
  }

  protected def channelParse(msg: Packet): Unit = {
    msg.id match {
      case SMSG_AUTH_CHALLENGE => handle_SMSG_AUTH_CHALLENGE(msg)
      case SMSG_AUTH_RESPONSE => handle_SMSG_AUTH_RESPONSE(msg)
      case SMSG_NAME_QUERY => handle_SMSG_NAME_QUERY(msg)
      case SMSG_CHAR_ENUM => handle_SMSG_CHAR_ENUM(msg)
      case SMSG_LOGIN_VERIFY_WORLD => handle_SMSG_LOGIN_VERIFY_WORLD(msg)
      case SMSG_GUILD_QUERY => handle_SMSG_GUILD_QUERY(msg)
      case SMSG_GUILD_EVENT => handle_SMSG_GUILD_EVENT(msg)
      case SMSG_GUILD_ROSTER => handle_SMSG_GUILD_ROSTER(msg)
      case SMSG_MESSAGECHAT => handle_SMSG_MESSAGECHAT(msg)
      case SMSG_CHANNEL_NOTIFY => handle_SMSG_CHANNEL_NOTIFY(msg)
      case SMSG_NOTIFICATION => handle_SMSG_NOTIFICATION(msg)
      case SMSG_WHO => handle_SMSG_WHO(msg)
      case SMSG_SERVER_MESSAGE => handle_SMSG_SERVER_MESSAGE(msg)
      case SMSG_INVALIDATE_PLAYER => handle_SMSG_INVALIDATE_PLAYER(msg)

      case SMSG_WARDEN_DATA => handle_SMSG_WARDEN_DATA(msg)

      case SMSG_TRADE_STATUS => handle_SMSG_TRADE_STATUS(msg)
      case SMSG_TRADE_STATUS_EXTENDED => handle_SMSG_TRADE_STATUS_EXTENDED(msg)

      case unhandled =>
    }
  }

  private def handle_SMSG_AUTH_CHALLENGE(msg: Packet): Unit = {
    val authChallengeMessage = parseAuthChallenge(msg)

    ctx.get.channel.attr(CRYPT).get.init(authChallengeMessage.sessionKey)

    ctx.get.writeAndFlush(Packet(CMSG_AUTH_CHALLENGE, authChallengeMessage.byteBuf))
  }

  protected def parseAuthChallenge(msg: Packet): AuthChallengeMessage = {
    val account = Global.config.wow.account

    val serverSeed = msg.byteBuf.readInt
    val clientSeed = Random.nextInt
    val out = PooledByteBufAllocator.DEFAULT.buffer(200, 400)
    out.writeShortLE(0)
    out.writeIntLE(WowChatConfig.getGameBuild)
    out.writeIntLE(0)
    out.writeBytes(account)
    out.writeByte(0)
    out.writeInt(clientSeed)

    val md = MessageDigest.getInstance("SHA1")
    md.update(account)
    md.update(Array[Byte](0, 0, 0, 0))
    md.update(ByteUtils.intToBytes(clientSeed))
    md.update(ByteUtils.intToBytes(serverSeed))
    md.update(sessionKey)
    out.writeBytes(md.digest)

    out.writeBytes(addonInfo)

    AuthChallengeMessage(sessionKey, out)
  }

  private def handle_SMSG_AUTH_RESPONSE(msg: Packet): Unit = {
    val code = parseAuthResponse(msg)
    if (code == AuthResponseCodes.AUTH_OK) {
      logger.info("Successfully logged in!")
      sendCharEnum
    } else if (code == AuthResponseCodes.AUTH_WAIT_QUEUE) {
      if (msg.byteBuf.readableBytes() >= 14) {
        msg.byteBuf.skipBytes(10)
      }
      val position = msg.byteBuf.readIntLE
      logger.info(s"Queue enabled. Position: $position")
    } else {
      logger.error(AuthResponseCodes.getMessage(code))
      ctx.foreach(_.close)
      gameEventCallback.error
    }
  }

  private def sendCharEnum: Unit = {
    // Only request char enum if previous requests were unsuccessful (due to failed warden reply)
    if (!receivedCharEnum) {
      ctx.get.writeAndFlush(Packet(CMSG_CHAR_ENUM))
    }
  }

  protected def parseAuthResponse(msg: Packet): Byte = {
    msg.byteBuf.readByte
  }

  private def handle_SMSG_NAME_QUERY(msg: Packet): Unit = {
    val nameQueryMessage = parseNameQuery(msg)

    if (nameQueryMessage.guid == lastTradePlayerGuid) {
      lastTradePlayerName = Some(nameQueryMessage.name)
      logger.info(s"[Trade] Nombre del jugador del trade: ${nameQueryMessage.name}")
    }

    queuedChatMessages
      .remove(nameQueryMessage.guid)
      .foreach(messages => {
        messages.foreach(message => {
          Global.discord.sendMessageFromWow(Some(nameQueryMessage.name), message.message, message.tp, message.channel)
        })
        playerRoster += nameQueryMessage.guid -> Player(nameQueryMessage.name, nameQueryMessage.charClass)
    })
  }

  protected def parseNameQuery(msg: Packet): NameQueryMessage = {
    val guid = msg.byteBuf.readLongLE
    val name = msg.readString
    msg.skipString // realm name for cross bg usage
    msg.byteBuf.skipBytes(4) // race
    msg.byteBuf.skipBytes(4) // gender
    val charClass = msg.byteBuf.readIntLE.toByte

    NameQueryMessage(guid, name, charClass)
  }

  private def handle_SMSG_CHAR_ENUM(msg: Packet): Unit = {
    if (receivedCharEnum) {
      if (inWorld) {
        // Do not parse char enum again if we've already joined the world.
        return
      } else {
        logger.info("Received character enum more than once. Trying to join the world again...")
      }
    }
    receivedCharEnum = true
    parseCharEnum(msg).fold({
      logger.error(s"Character ${Global.config.wow.character} not found!")
    })(character => {
      logger.info(s"Logging in with character ${character.name}")
      selfCharacterId = Some(character.guid)
      languageId = Races.getLanguage(character.race)
      guildGuid = character.guildGuid

      val out = PooledByteBufAllocator.DEFAULT.buffer(16, 16) // increase to 16 for MoP
      writePlayerLogin(out)
      ctx.get.writeAndFlush(Packet(CMSG_PLAYER_LOGIN, out))
    })
  }

  protected def parseCharEnum(msg: Packet): Option[CharEnumMessage] = {
    val characterBytes = Global.config.wow.character.toLowerCase.getBytes("UTF-8")
    val charactersNum = msg.byteBuf.readByte

    // only care about guid and name here
    (0 until charactersNum).foreach(i => {
      val guid = msg.byteBuf.readLongLE
      val name = msg.readString
      val race = msg.byteBuf.readByte // will determine what language to use in chat

      msg.byteBuf.skipBytes(1) // class
      msg.byteBuf.skipBytes(1) // gender
      msg.byteBuf.skipBytes(1) // skin
      msg.byteBuf.skipBytes(1) // face
      msg.byteBuf.skipBytes(1) // hair style
      msg.byteBuf.skipBytes(1) // hair color
      msg.byteBuf.skipBytes(1) // facial hair
      msg.byteBuf.skipBytes(1) // level
      msg.byteBuf.skipBytes(4) // zone
      msg.byteBuf.skipBytes(4) // map - could be useful in the future to determine what city specific channels to join

      msg.byteBuf.skipBytes(12) // x + y + z

      val guildGuid = msg.byteBuf.readIntLE
      if (name.toLowerCase.getBytes("UTF-8").sameElements(characterBytes)) {
        return Some(CharEnumMessage(name, guid, race, guildGuid))
      }

      msg.byteBuf.skipBytes(4) // character flags
      msg.byteBuf.skipBytes(1) // first login
      msg.byteBuf.skipBytes(12) // pet info
      msg.byteBuf.skipBytes(19 * 5) // equipment info
      msg.byteBuf.skipBytes(5) // first bag display info
    })
    None
  }

  protected def writePlayerLogin(out: ByteBuf): Unit = {
    out.writeLongLE(selfCharacterId.get)
  }

  private def handle_SMSG_LOGIN_VERIFY_WORLD(msg: Packet): Unit = {
    // for some reason some servers send this packet more than once.
    if (inWorld) {
      return
    }

    logger.info("Successfully joined the world!")
    inWorld = true
    Global.discord.changeRealmStatus(realmName)
    gameEventCallback.connected
    runKeepAliveExecutor
    runGuildRosterExecutor
    if (guildGuid != 0) {
      queryGuildName
      updateGuildRoster
    }

    // join channels
    Global.config.channels
      .flatMap(channelConfig => {
        channelConfig.wow.channel.fold[Option[(Int, String)]](None)(channelName => {
          Some(channelConfig.wow.id.getOrElse(ChatChannelIds.getId(channelName)) -> channelName)
        })
      })
      .foreach {
        case (id, name) =>
        logger.info(s"Joining channel $name")
        val byteBuf = PooledByteBufAllocator.DEFAULT.buffer(50, 200)
        writeJoinChannel(byteBuf, id, name.getBytes("UTF-8"))
        ctx.get.writeAndFlush(Packet(CMSG_JOIN_CHANNEL, byteBuf))
      }
  }

  protected def writeJoinChannel(out: ByteBuf, id: Int, utf8ChannelBytes: Array[Byte]): Unit = {
    out.writeBytes(utf8ChannelBytes)
    out.writeByte(0)
    out.writeByte(0)
  }

  private def handle_SMSG_GUILD_QUERY(msg: Packet): Unit = {
    guildInfo = handleGuildQuery(msg)
  }

  protected def handleGuildQuery(msg: Packet): GuildInfo = {
    msg.byteBuf.skipBytes(4)
    val name = msg.readString

    val ranks = (0 until 10)
      .map(_ -> msg.readString)
      .filter {
        case (_, name) => name.nonEmpty
      }
      .toMap

    GuildInfo(name, ranks)
  }

  private def handle_SMSG_GUILD_EVENT(msg: Packet): Unit = {
    val event = msg.byteBuf.readByte
    val numStrings = msg.byteBuf.readByte
    val messages = (0 until numStrings).map(i => msg.readString)

    handleGuildEvent(event, messages)
  }

  protected def handleGuildEvent(event: Byte, messages: Seq[String]): Unit = {
    // ignore empty messages
    if (messages.forall(_.trim.isEmpty)) {
      return
    }

    // ignore events from self
    if (event != GuildEvents.GE_MOTD && Global.config.wow.character.equalsIgnoreCase(messages.head)) {
      return
    }

    val eventConfigKey = event match {
      case GuildEvents.GE_PROMOTED => "promoted"
      case GuildEvents.GE_DEMOTED => "demoted"
      case GuildEvents.GE_MOTD => "motd"
      case GuildEvents.GE_JOINED => "joined"
      case GuildEvents.GE_LEFT => "left"
      case GuildEvents.GE_REMOVED => "removed"
      case GuildEvents.GE_SIGNED_ON => "online"
      case GuildEvents.GE_SIGNED_OFF => "offline"
      case _ => return
    }

    val guildNotificationConfig = Global.config.guildConfig.notificationConfigs(eventConfigKey)

    if (guildNotificationConfig.enabled) {
      val formatted = event match {
        case GuildEvents.GE_PROMOTED | GuildEvents.GE_DEMOTED =>
          guildNotificationConfig.format
            .replace("%time", Global.getTime)
            .replace("%user", messages.head)
            .replace("%message", messages.head)
            .replace("%target", messages(1))
            .replace("%rank", messages(2))
        case GuildEvents.GE_REMOVED =>
          guildNotificationConfig.format
            .replace("%time", Global.getTime)
            .replace("%user", messages(1))
            .replace("%message", messages(1))
            .replace("%target", messages.head)
        case _ =>
          guildNotificationConfig.format
            .replace("%time", Global.getTime)
            .replace("%user", messages.head)
            .replace("%message", messages.head)
      }

      Global.discord.sendGuildNotification(eventConfigKey, formatted)
    }

    updateGuildRoster
  }

  private def handle_SMSG_GUILD_ROSTER(msg: Packet): Unit = {
    guildRoster.clear
    guildRoster ++= parseGuildRoster(msg)
    updateGuildiesOnline
  }

  protected def parseGuildRoster(msg: Packet): Map[Long, GuildMember] = {
    val count = msg.byteBuf.readIntLE
    guildMotd = Some(msg.readString)
    val ginfo = msg.readString
    val rankscount = msg.byteBuf.readIntLE
    (0 until rankscount).foreach(i => msg.byteBuf.skipBytes(4))
    (0 until count).map(i => {
      val guid = msg.byteBuf.readLongLE
      val isOnline = msg.byteBuf.readBoolean
      val name = msg.readString
      msg.byteBuf.skipBytes(4) // guild rank
      val level = msg.byteBuf.readByte
      val charClass = msg.byteBuf.readByte
      val zoneId = msg.byteBuf.readIntLE
      val lastLogoff = if (!isOnline) {
        msg.byteBuf.readFloatLE
      } else {
        0
      }
      msg.skipString
      msg.skipString

      guid -> GuildMember(name, isOnline, charClass, level, zoneId, lastLogoff)
    }).toMap
  }

  protected def handle_SMSG_MESSAGECHAT(msg: Packet): Unit = {
    logger.debug(s"RECV CHAT: ${ByteUtils.toHexString(msg.byteBuf, true, true)}")
    parseChatMessage(msg).foreach(sendChatMessage)
  }

  protected def sendChatMessage(chatMessage: ChatMessage): Unit = {
    if (chatMessage.guid == 0) {
      Global.discord.sendMessageFromWow(None, chatMessage.message, chatMessage.tp, None)
    } else {
      playerRoster.get(chatMessage.guid).fold({
        queuedChatMessages.get(chatMessage.guid).fold({
          queuedChatMessages += chatMessage.guid -> ListBuffer(chatMessage)
          sendNameQuery(chatMessage.guid)
        })(_ += chatMessage)
      })(name => {
        Global.discord.sendMessageFromWow(Some(name.name), chatMessage.message, chatMessage.tp, chatMessage.channel)
      })
    }
  }

  protected def parseChatMessage(msg: Packet): Option[ChatMessage] = {
    val tp = msg.byteBuf.readByte

    val lang = msg.byteBuf.readIntLE
    // ignore addon messages
    if (lang == -1) {
      return None
    }

    val channelName = if (tp == ChatEvents.CHAT_MSG_CHANNEL) {
      val ret = Some(msg.readString)
      msg.byteBuf.skipBytes(4)
      ret
    } else {
      None
    }

    // ignore if from an unhandled channel
    if (!Global.wowToDiscord.contains((tp, channelName.map(_.toLowerCase)))) {
      return None
    }

    // ignore messages from itself, unless it is a system message.
    val guid = msg.byteBuf.readLongLE
    if (tp != ChatEvents.CHAT_MSG_SYSTEM && guid == selfCharacterId.get) {
      return None
    }

    // these events have a "target" guid we need to skip
    tp match {
      case ChatEvents.CHAT_MSG_SAY |
           ChatEvents.CHAT_MSG_YELL =>
        msg.byteBuf.skipBytes(8)
      case _ =>
    }

    val txtLen = msg.byteBuf.readIntLE
    val txt = msg.byteBuf.readCharSequence(txtLen - 1, Charset.forName("UTF-8")).toString

    Some(ChatMessage(guid, tp, txt, channelName))
  }

  private def handle_SMSG_CHANNEL_NOTIFY(msg: Packet): Unit = {
    val id = msg.byteBuf.readByte
    val channelName = msg.readString

    id match {
      case ChatNotify.CHAT_YOU_JOINED_NOTICE =>
        logger.info(s"Joined Channel: [$channelName]")
      case ChatNotify.CHAT_WRONG_PASSWORD_NOTICE =>
        logger.error(s"Wrong password for $channelName.")
      case ChatNotify.CHAT_MUTED_NOTICE =>
        logger.error(s"[$channelName] You do not have permission to speak.")
      case ChatNotify.CHAT_BANNED_NOTICE =>
        logger.error(s"[$channelName] You are banned from that channel.")
      case ChatNotify.CHAT_WRONG_FACTION_NOTICE =>
        logger.error(s"Wrong alliance for $channelName.")
      case ChatNotify.CHAT_INVALID_NAME_NOTICE =>
        logger.error("Invalid channel name")
      case ChatNotify.CHAT_THROTTLED_NOTICE =>
        logger.error(s"[$channelName] The number of messages that can be sent to this channel is limited, please wait to send another message.")
      case ChatNotify.CHAT_NOT_IN_AREA_NOTICE =>
        logger.error(s"[$channelName] You are not in the correct area for this channel.")
      case ChatNotify.CHAT_NOT_IN_LFG_NOTICE =>
        logger.error(s"[$channelName] You must be queued in looking for group before joining this channel.")
      case _ =>
        // ignore all other chat notifications
    }
  }

  private def handle_SMSG_NOTIFICATION(msg: Packet): Unit = {
    logger.info(s"Notification: ${parseNotification(msg)}")
  }

  protected def parseNotification(msg: Packet): String = {
    msg.readString
  }

  // This is actually really hard to map back to a specific request
  // because the packet doesn't include a cookie/id/requested name if none found
  private def handle_SMSG_WHO(msg: Packet): Unit = {
    val displayResults = parseWhoResponse(msg)
    // Try to find exact match
    val exactName = CommandHandler.whoRequest.playerName.toLowerCase
    val exactMatch = displayResults.find(_.playerName.toLowerCase == exactName)
    val handledResponses = CommandHandler.handleWhoResponse(
      exactMatch,
      guildInfo,
      guildRoster,
      guildMember => guildMember.name.equalsIgnoreCase(CommandHandler.whoRequest.playerName)
    )
    if (handledResponses.isEmpty) {
      // Exact match not found and no exact match in guild roster. Look for approximate matches.
      if (displayResults.isEmpty) {
        // No approximate matches found online. Try to find some in guild roster.
        val approximateMatches = CommandHandler.handleWhoResponse(
          exactMatch,
          guildInfo,
          guildRoster,
          guildMember => guildMember.name.toLowerCase.contains(exactName)
        )
        if (approximateMatches.isEmpty) {
          // No approximate matches found.
          CommandHandler.whoRequest.messageChannel.sendMessage(s"No player named ${CommandHandler.whoRequest.playerName} is currently playing.").queue()
        } else {
          // Send at most 3 approximate matches.
          approximateMatches.take(3).foreach(CommandHandler.whoRequest.messageChannel.sendMessage(_).queue())
        }
      } else {
        // Approximate matches found online!
        displayResults.take(3).foreach(whoResponse => {
          CommandHandler.handleWhoResponse(Some(whoResponse),
            guildInfo,
            guildRoster,
            guildMember => guildMember.name.equalsIgnoreCase(CommandHandler.whoRequest.playerName)
          ).foreach(CommandHandler.whoRequest.messageChannel.sendMessage(_).queue())
        })
      }
    } else {
      handledResponses.foreach(CommandHandler.whoRequest.messageChannel.sendMessage(_).queue())
    }
  }

  protected def parseWhoResponse(msg: Packet): Seq[WhoResponse] = {
    val displayCount = msg.byteBuf.readIntLE
    val matchCount = msg.byteBuf.readIntLE

    if (displayCount == 0) {
      Seq.empty
    } else {
      (0 until displayCount).map(i => {
        val playerName = msg.readString
        val guildName = msg.readString
        val lvl = msg.byteBuf.readIntLE
        val cls = Classes.valueOf(msg.byteBuf.readIntLE.toByte)
        val race = Races.valueOf(msg.byteBuf.readIntLE.toByte)
        val gender = if (WowChatConfig.getExpansion != WowExpansion.Vanilla) {
          Some(Genders.valueOf(msg.byteBuf.readByte)) // tbc/wotlk only
        } else {
          None
        }
        val zone = msg.byteBuf.readIntLE
        WhoResponse(
          playerName,
          guildName,
          lvl,
          cls,
          race,
          gender,
          GameResources.AREA.getOrElse(zone, "Unknown Zone")
        )
      })
    }
  }

  private def handle_SMSG_SERVER_MESSAGE(msg: Packet): Unit = {
    val tp = msg.byteBuf.readIntLE
    val txt = msg.readString
    val message = tp match {
      case ServerMessageType.SERVER_MSG_SHUTDOWN_TIME => s"Shutdown in $txt"
      case ServerMessageType.SERVER_MSG_RESTART_TIME => s"Restart in $txt"
      case ServerMessageType.SERVER_MSG_SHUTDOWN_CANCELLED => "Shutdown cancelled."
      case ServerMessageType.SERVER_MSG_RESTART_CANCELLED => "Restart cancelled."
      case _ => txt
    }
    sendChatMessage(ChatMessage(0, ChatEvents.CHAT_MSG_SYSTEM, message, None))
  }

  private def handle_SMSG_INVALIDATE_PLAYER(msg: Packet): Unit = {
    val guid = parseInvalidatePlayer(msg)
    playerRoster.remove(guid)
  }

  protected def parseInvalidatePlayer(msg: Packet): Long = {
    msg.byteBuf.readLongLE
  }

  private def handle_SMSG_WARDEN_DATA(msg: Packet): Unit = {
    if (Global.config.wow.platform == Platform.Windows) {
      logger.error("WARDEN ON WINDOWS IS NOT SUPPORTED! BOT WILL SOON DISCONNECT! TRY TO USE PLATFORM MAC!")
      return
    }

    if (wardenHandler.isEmpty) {
      wardenHandler = Some(initializeWardenHandler)
      logger.info("Warden handling initialized!")
    }

    val (id, out) = wardenHandler.get.handle(msg)
    if (out.isDefined) {
      ctx.get.writeAndFlush(Packet(CMSG_WARDEN_DATA, out.get))
      // Sometimes servers do not allow char listing request until warden is successfully answered.
      // Try requesting it here again.
      if (id == WardenPackets.WARDEN_SMSG_HASH_REQUEST) {
        sendCharEnum
      }
    }
  }

  protected def initializeWardenHandler: WardenHandler = {
    new WardenHandler(sessionKey)
  }

  override def setGoldPickerMessage(message: String): Unit = {
    goldPickerMessage = message
  }

  override def setGoldPickerMinDelay(delay: Int): Unit = {
    goldPickerMinDelay = delay
  }

  override def setGoldPickerMaxDelay(delay: Int): Unit = {
    goldPickerMaxDelay = delay
  }

  override def getGoldPickerMinDelay(): Int = {
    goldPickerMinDelay
  }

  override def getGoldPickerMaxDelay(): Int = {
    goldPickerMaxDelay
  }

  override def getGoldPickerMessage(): String = {
    goldPickerMessage
  }

  override def getGoldPickerStatus: String = {
    s"""GoldPicker está ${if (isGoldPickerActive) "activo" else "inactivo"}.
       |Mensaje actual: $goldPickerMessage
       |Delay mínimo: $goldPickerMinDelay segundos
       |Delay máximo: $goldPickerMaxDelay segundos""".stripMargin
  }

  override def startGoldPicker(): Unit = {
    stopGoldPicker() // Asegurarse de que no haya una tarea previa en ejecución
    _isGoldPickerActive = true
    goldPickerFuture = Some(runAutoSayExecutor)
  }

  override def stopGoldPicker(): Unit = {
    _isGoldPickerActive = false
    goldPickerFuture.foreach(_.cancel(false))
    goldPickerFuture = None
  }

  private def runAutoSayExecutor: ScheduledFuture[_] = {
    def scheduleNextMessage(): ScheduledFuture[_] = {
      val delay = random.nextInt(goldPickerMaxDelay - goldPickerMinDelay + 1) + goldPickerMinDelay
      executorService.schedule(new Runnable {
        override def run(): Unit = {
          if (inWorld && isGoldPickerActive) {
            sendSayMessage(goldPickerMessage)
            goldPickerFuture = Some(scheduleNextMessage())
          }
        }
      }, delay, TimeUnit.SECONDS)
    }

    scheduleNextMessage()
  }

  private def sendSayMessage(message: String): Unit = {
    ctx.foreach { ctx =>
      val byteBuf = PooledByteBufAllocator.DEFAULT.buffer(200, 400)
      byteBuf.writeIntLE(ChatEvents.CHAT_MSG_SAY)
      byteBuf.writeIntLE(languageId)
      byteBuf.writeBytes(message.getBytes("UTF-8"))
      byteBuf.writeByte(0)
      ctx.writeAndFlush(Packet(CMSG_MESSAGECHAT, byteBuf))
    }
  }

  // AutoFlood methods
  def setAutoFloodMessage(message: String): Unit = {
    autoFloodMessage = message
  }

  def setAutoFloodDelay(delay: Int): Unit = {
    autoFloodDelay = delay
  }

  def setAutoFloodChannels(channels: Set[String]): Unit = {
    autoFloodChannels = channels
  }

  def getAutoFloodStatus: String = {
    s"""AutoFlood está ${if (_isAutoFloodActive) "activo" else "inactivo"}.
       |Mensaje actual: $autoFloodMessage
       |Delay: $autoFloodDelay segundos
       |Canales: ${if (autoFloodChannels.isEmpty) "ninguno" else autoFloodChannels.mkString(", ")}""".stripMargin
  }

  def startAutoFlood(): Unit = {
    stopAutoFlood() // Asegurarse de que no haya una tarea previa en ejecución
    if (autoFloodMessage.isEmpty || autoFloodChannels.isEmpty) {
      logger.warn("No se puede iniciar AutoFlood: mensaje o canales no configurados")
      return
    }
    _isAutoFloodActive = true
    autoFloodFuture = Some(runAutoFloodExecutor)
  }

  def stopAutoFlood(): Unit = {
    _isAutoFloodActive = false
    autoFloodFuture.foreach(_.cancel(false))
    autoFloodFuture = None
  }

  private def runAutoFloodExecutor: ScheduledFuture[_] = {
    def scheduleNextMessage(): ScheduledFuture[_] = {
      executorService.schedule(new Runnable {
        override def run(): Unit = {
          if (inWorld && _isAutoFloodActive) {
            autoFloodChannels.foreach(channel => {
              channel.toLowerCase match {
                case "yell" => sendYellMessage(autoFloodMessage)
                case "say" => sendSayMessage(autoFloodMessage)
                case channelId => sendChannelMessage(autoFloodMessage, channelId)
              }
            })
            autoFloodFuture = Some(scheduleNextMessage())
          }
        }
      }, autoFloodDelay, TimeUnit.SECONDS)
    }
    scheduleNextMessage()
  }

  private def sendYellMessage(message: String): Unit = {
    ctx.foreach { ctx =>
      val byteBuf = PooledByteBufAllocator.DEFAULT.buffer(200, 400)
      byteBuf.writeIntLE(ChatEvents.CHAT_MSG_YELL)
      byteBuf.writeIntLE(languageId)
      byteBuf.writeBytes(message.getBytes("UTF-8"))
      byteBuf.writeByte(0)
      ctx.writeAndFlush(Packet(CMSG_MESSAGECHAT, byteBuf))
    }
  }

  private def sendChannelMessage(message: String, channelName: String): Unit = {
    ctx.foreach { ctx =>
      val byteBuf = PooledByteBufAllocator.DEFAULT.buffer(200, 400)
      byteBuf.writeIntLE(ChatEvents.CHAT_MSG_CHANNEL)
      byteBuf.writeIntLE(languageId)
      byteBuf.writeBytes(channelName.getBytes("UTF-8"))
      byteBuf.writeByte(0)
      byteBuf.writeBytes(message.getBytes("UTF-8"))
      byteBuf.writeByte(0)
      ctx.writeAndFlush(Packet(CMSG_MESSAGECHAT, byteBuf))
    }
  }

  private def handle_SMSG_TRADE_STATUS(msg: Packet): Unit = {
    val id = msg.byteBuf.readByte
    logger.info(s"[Trade][IN] SMSG_TRADE_STATUS $id")

    if (id == 1) { // Contestamos al inicio del trade "iniciando" el trade desde nuestro cliente también
      msg.byteBuf.skipBytes(3)
      lastTradePlayerGuid = msg.byteBuf.readLongLE()
      logger.info(s"[Trade] Trade iniciado por GUID: $lastTradePlayerGuid")
      sendNameQuery(lastTradePlayerGuid)

      logger.info("[Trade][OUT] CMSG_BEGIN_TRADE")
      ctx.get.writeAndFlush(Packet(CMSG_BEGIN_TRADE))
    }
    if (id == 4) { // Cuando aceptan el trade, enviamos el packet de aceptar también nosotros
      val delaySeconds = Random.nextInt(3) + 1 // Genera un número aleatorio entre 1 y 3
      logger.info(s"[Trade] Trade aceptado por la otra parte. Esperando $delaySeconds segundos antes de aceptar.")

      executorServiceShortTasks.schedule(new Runnable {
        override def run(): Unit = {
          logger.info("[Trade][OUT] CMSG_ACCEPT_TRADE")
          val byteBuf = PooledByteBufAllocator.DEFAULT.buffer(8, 8)
          byteBuf.writeIntLE(0x07.toByte)
          ctx.get.writeAndFlush(Packet(CMSG_ACCEPT_TRADE, byteBuf))
        }
      }, delaySeconds, TimeUnit.SECONDS)
    }
    if (id == 8) { // Trade completado correctamente
      // Replicamos lo que hace el cliente de wow
      ctx.get.writeAndFlush(Packet(CMSG_CANCEL_TRADE))
      ctx.get.writeAndFlush(Packet(CMSG_CANCEL_TRADE))
      ctx.get.writeAndFlush(Packet(CMSG_CANCEL_TRADE))

      // Anunciar el oro recibido en Discord
      if (lastTradeGoldAmount > 0) {
        val playerName = lastTradePlayerName.getOrElse("Jugador desconocido")
        val formattedGold = formatGold(lastTradeGoldAmount)
        val message = s"Trade completado con $playerName. Oro recibido: $formattedGold"
        Global.discord.sendMessageFromWow(None, message, ChatEvents.CHAT_MSG_OFFICER, None)
        lastTradeGoldAmount = 0 // Reiniciar el valor después de anunciarlo
        lastTradePlayerName = None // Reiniciar el nombre del jugador
      }
    }
  }

  private def handle_SMSG_TRADE_STATUS_EXTENDED(msg: Packet): Unit = {
    msg.byteBuf.skipBytes(13) // skip hasta el oro

    val gold = msg.byteBuf.readIntLE
    logger.info(s"[SMSG_TRADE_STATUS_EXTENDED][IN] GOLD: $gold")

    lastTradeGoldAmount = gold // Actualizar la variable global
  }

  private def formatGold(amount: Int): String = {
    val gold = amount / 10000
    val silver = (amount % 10000) / 100
    val copper = amount % 100

    val parts = Seq(
      if (gold > 0) s"${gold}g" else "",
      if (silver > 0) s"${silver}s" else "",
      if (copper > 0) s"${copper}c" else ""
    ).filter(_.nonEmpty)

    parts.mkString(" ")
  }
}
