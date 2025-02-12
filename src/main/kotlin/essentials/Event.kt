package essentials

import arc.ApplicationListener
import arc.Core
import arc.Events
import arc.files.Fi
import arc.func.Cons2
import arc.graphics.Color
import arc.struct.ObjectMap
import arc.struct.Seq
import arc.util.Align
import arc.util.Log
import arc.util.Time
import com.cybozu.labs.langdetect.DetectorFactory
import com.cybozu.labs.langdetect.LangDetectException
import essentials.Main.Companion.database
import mindustry.Vars.*
import mindustry.content.Blocks
import mindustry.content.Fx
import mindustry.content.UnitTypes
import mindustry.content.Weathers
import mindustry.entities.Damage
import mindustry.game.EventType
import mindustry.game.EventType.*
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Playerc
import mindustry.io.SaveIO
import mindustry.maps.Map
import mindustry.net.Packets
import mindustry.net.WorldReloader
import mindustry.world.Block
import org.hjson.JsonArray
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.regex.Pattern
import kotlin.experimental.and
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max


object Event {
    var order = 0
    val players = JsonArray()
    var orignalBlockMultiplier = 1f
    var orignalUnitMultiplier = 1f

    var voting = false
    var voteType: String? = null
    var voteTarget: Playerc? = null
    var voteTargetUUID: String? = null
    var voteReason: String? = null
    var voteMap: Map? = null
    var voteWave: Int? = null
    var voteStarter: Playerc? = null
    var voted = Seq<String>()
    var lastVoted = LocalTime.now()
    var isAdminVote = false
    var isCanceled = false

    var enemyCores = 0
    var enemyCoresCounted = false

    private val worldHistory = Seq<TileLog>()
    var playerHistory = Seq<PlayerLog>()

    private var random = Random()
    private var dateformat = SimpleDateFormat("HH:mm:ss")

    private var blockExp = ObjectMap<String, Int>()

    init {
        val aa = arrayOf("af", "ar", "bg", "bn", "cs", "da", "de", "el", "en", "es", "et", "fa", "fi", "fr", "gu", "he", "hi", "hr", "hu", "id", "it", "ja", "kn", "ko", "lt", "lv", "mk", "ml", "mr", "ne", "nl", "no", "pa", "pl", "pt", "ro", "ru", "sk", "sl", "so", "sq", "sv", "sw", "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh-cn", "zh-tw")
        val bb = arrayListOf<String>()
        for (a in aa) {
            bb.add(Main::class.java.classLoader.getResource("profiles/$a")!!.readText(Charset.forName("UTF-8")))
        }
        DetectorFactory.loadProfile(bb)
    }

    fun register() {
        Events.on(PlayerChatEvent::class.java) {
            if (!it.message.startsWith("/")) {
                val data = findPlayerData(it.player.uuid())
                if (data != null) {
                    log(LogType.Chat, "${it.player.name}: ${it.message}")
                    Log.info("<&y" + it.player.name + ": &lm" + it.message + "&lg>")

                    if (!data.mute) {
                        val isAdmin = Permission.check(it.player, "vote.pass")
                        if (voting && it.message.equals("y", true) && !voted.contains(it.player.uuid())) {
                            if (isAdmin) {
                                isAdminVote = true
                            } else {
                                voted.add(it.player.uuid())
                            }
                            it.player.sendMessage(Bundle(data.languageTag)["command.vote.voted"])
                        } else if (voting && it.message.equals("n", true) && isAdmin) {
                            isCanceled = true
                        }

                        if (Config.chatlimit) {
                            val d = DetectorFactory.create()
                            val languages = Config.chatlanguage.split(",")
                            d.append(it.message)
                            try {
                                if (!languages.contains(d.detect()) && (voting && it.message.equals("y", true) && !voted.contains(it.player.uuid()))) {
                                    it.player.sendMessage(Bundle(data.languageTag)["chat.language.not.allow"])
                                    return@on
                                }
                            } catch (_: LangDetectException) {
                            }
                        }

                        if (Config.chatBlacklist) {
                            val file = Main.root.child("chat_blacklist.txt").readString("UTF-8").split("\r\n")
                            if (file.isNotEmpty()) {
                                for (a in file) {
                                    if (Config.chatBlacklistRegex) {
                                        if (it.message.contains(Regex(a))) {
                                            it.player.sendMessage(Bundle(findPlayerData(it.player.uuid())!!.languageTag)["chat.blacklisted"])
                                            return@on
                                        }
                                    } else {
                                        if (it.message.contains(a)) {
                                            it.player.sendMessage(Bundle(findPlayerData(it.player.uuid())!!.languageTag)["chat.blacklisted"])
                                            return@on
                                        }
                                    }
                                }
                            }
                        }
                        Call.sendMessage(Permission[it.player].chatFormat.replace("%1", it.player.coloredName()).replace("%2", it.message), it.message, it.player)
                    }
                } else {
                    Call.sendMessage("[gray]${it.player.name} [orange] > [white]${it.message}", it.message, it.player)
                }
            }
        }

        Events.on(WithdrawEvent::class.java) {
            if (it.tile != null && it.player.unit().item() != null && it.player.name != null) {
                log(LogType.WithDraw, "${it.player.name} puts ${it.player.unit().item().name} ${it.amount} amount into ${it.tile.block().name}.")
            }
        }

        Events.on(DepositEvent::class.java) {
            if (it.tile != null && it.player.unit().item() != null && it.player.name != null) {
                log(LogType.Deposit, "${it.player.name} puts ${it.player.unit().item().name} ${it.amount} amount into ${it.tile.block().name}.")
            }
        }

        Events.on(ConfigEvent::class.java) { // todo 전력 노드 오작동
            if (it.tile != null && it.tile.block() != null && it.player != null && it.value is Int) {
                if (Config.antiGrief) {
                    val entity = it.tile
                    val other = world.tile(it.value as Int)
                    val valid = other != null && entity.power != null && other.block().hasPower && other.block().outputsPayload
                    if (valid) {
                        val oldGraph = entity.power.graph
                        val newGraph = other.build.power.graph
                        val oldGraphCount = oldGraph.toString().substring(oldGraph.toString().indexOf("all=["), oldGraph.toString().indexOf("], graph")).replaceFirst("all=\\[".toRegex(), "").split(",").toTypedArray().size
                        val newGraphCount = newGraph.toString().substring(newGraph.toString().indexOf("all=["), newGraph.toString().indexOf("], graph")).replaceFirst("all=\\[".toRegex(), "").split(",").toTypedArray().size
                        if (abs(oldGraphCount - newGraphCount) > 10) {
                            Groups.player.forEach { a ->
                                val data = findPlayerData(a.uuid())
                                if (data != null) {
                                    val bundle = Bundle(data.languageTag)
                                    a.sendMessage(bundle["event.antigrief.node", it.player.name, oldGraphCount.coerceAtLeast(newGraphCount), oldGraphCount.coerceAtMost(newGraphCount), "${it.tile.x}, ${it.tile.y}"])
                                }
                            }
                        }
                    }
                }

                addLog(TileLog(System.currentTimeMillis(), it.player.name, "config", it.tile.tile.x, it.tile.tile.y, it.tile.block().name))
                addLog(PlayerLog(it.player.name, it.tile.block(), it.tile.tile.build.rotation, it.tile.tile.x, it.tile.tile.y, "config", it.player.team(), it.tile.tile.block().configurations))
            }
        }

        Events.on(TapEvent::class.java) {
            log(LogType.Tap, "${it.player.name} clicks on ${it.tile.block().name}")
            addLog(TileLog(System.currentTimeMillis(), it.player.name, "tap", it.tile.x, it.tile.y, it.tile.block().name))
            val data = findPlayerData(it.player.uuid())
            if (data != null) {
                for (a in PluginData.warpBlocks) {
                    if (it.tile.x >= a.x && it.tile.x <= a.x && it.tile.y >= a.y && it.tile.y <= a.y) {
                        if (a.online) {
                            Log.info("${it.player.name} moves to server ${a.ip}:${a.port}")
                            Call.connect(it.player.con(), a.ip, a.port)
                        }
                        break
                    }
                }

                for (a in PluginData.warpZones) {
                    if (it.tile.x > a.startTile.x && it.tile.x < a.finishTile.x && it.tile.y > a.startTile.y && it.tile.y < a.finishTile.y) {
                        Log.info("${it.player.name} moves to server ${a.ip}:${a.port}")
                        Call.connect(it.player.con(), a.ip, a.port)
                        break
                    }
                }

                if (data.status.containsKey("log")) {
                    val buf = Seq<TileLog>()
                    for (a in worldHistory) {
                        if (a.x == it.tile.x && a.y == it.tile.y) {
                            buf.add(a)
                        }
                    }
                    val str = StringBuilder()
                    val bundle = Bundle(data.languageTag)
                    val coreBundle = ResourceBundle.getBundle(
                        "bundle_block", try {
                            when (data.languageTag) {
                                "ko" -> Locale.KOREA
                                else -> Locale.ENGLISH
                            }
                        } catch (e: Exception) {
                            Locale.ENGLISH
                        }
                    )

                    for (a in buf) {
                        val action = when (a.action) {
                            "tap" -> "[royal]${bundle["event.log.tap"]}[]"
                            "break" -> "[scarlet]${bundle["event.log.break"]}[]"
                            "place" -> "[sky]${bundle["event.log.place"]}[]"
                            "config" -> "[cyan]${bundle["event.log.config"]}[]"
                            else -> ""
                        }

                        str.append(bundle["event.log.format", dateformat.format(a.time), a.player, coreBundle.getString("block.${a.tile}.name"), action]).append("\n")
                    }

                    Call.effect(it.player.con(), Fx.shockwave, it.tile.getX(), it.tile.getY(), 0f, Color.cyan)
                    val str2 = StringBuilder()
                    if (str.toString().lines().size > 10) {
                        val lines: List<String> = str.toString().split("\n").reversed()
                        for (i in 0 until 10) {
                            str2.append(lines[i]).append("\n")
                        }
                        it.player.sendMessage(str2.toString())
                    } else {
                        it.player.sendMessage(str.toString())
                    }
                }

                for (a in database.players) {
                    if (a.status.containsKey("tracking")) {
                        Call.effect(a.player.con(), Fx.bigShockwave, it.tile.getX(), it.tile.getY(), 0f, Color.cyan)
                    }
                }
            }
        }

        Events.on(PickupEvent::class.java) {

        }

        Events.on(UnitControlEvent::class.java) {

        }

        Events.on(ServerLoadEvent::class.java) {
            content.blocks().each {
                var buf = 0
                for (b in it.requirements) {
                    buf = +b.amount
                }
                blockExp.put(it.name, buf)
            }

            if (!Config.blockIP && PluginData.status.contains("iptablesFirst")) {
                for (a in netServer.admins.banned) {
                    for (b in a.ips) {
                        val cmd = arrayOf("/bin/bash", "-c", "echo ${PluginData.sudoPassword}| sudo -S iptables -D INPUT -s $b -j DROP")
                        Runtime.getRuntime().exec(cmd)
                    }
                }
                PluginData.status.remove("iptablesFirst")
                Log.info(Bundle()["event.ban.iptables.remove"])
            } else if (Config.blockIP && !PluginData.status.contains("iptablesFirst")) {
                for (a in netServer.admins.banned) {
                    for (b in a.ips) {
                        val cmd = arrayOf("/bin/bash", "-c", "echo ${PluginData.sudoPassword}| sudo -S iptables -A INPUT -s $b -j DROP")
                        Runtime.getRuntime().exec(cmd)
                        Log.info(Bundle()["event.ban.iptables", b])
                    }
                }
                PluginData.status.add("iptablesFirst")
            }
        }

        Events.on(GameOverEvent::class.java) {
            worldHistory.clear()
            if (state.rules.pvp) {
                var index = 5
                for (a in 0..4) {
                    if (state.teams[Team.all[index]].cores.isEmpty) {
                        index--
                    }
                }
                if (index == 1) {
                    for (player in Groups.player) {
                        val target = findPlayerData(player.uuid())
                        if (target != null) {
                            if (player.team().name == it.winner.name) {
                                target.pvpwincount++
                            } else if (player.team().name != it.winner.name) {
                                target.pvplosecount++
                            }
                        }
                    }
                }
            } else if (state.rules.attackMode) {
                for (p in Groups.player) {
                    val target = findPlayerData(p.uuid())
                    if (target != null) {
                        val oldLevel = target.level
                        val oldExp = target.exp
                        val time = (PluginData.playtime.toInt() * 2) * enemyCores
                        var blockexp = 0

                        for (a in state.stats.placedBlockCount) {
                            blockexp += blockExp[a.key.name]
                        }

                        val bundle = Bundle(target.languageTag)

                        if (it.winner == p.team()) {
                            val score = (time + state.stats.enemyUnitsDestroyed + state.stats.unitsCreated + state.stats.buildingsBuilt) - (state.stats.buildingsDeconstructed + state.stats.buildingsDestroyed)

                            target.exp = target.exp + score + blockexp
                            target.attackclear++
                            p.sendMessage(bundle["exp.earn.victory", score + blockexp])
                        } else {
                            val score = (state.stats.enemyUnitsDestroyed + state.stats.unitsCreated + state.stats.buildingsBuilt) - (state.stats.buildingsDeconstructed + state.stats.buildingsDestroyed)

                            target.exp = target.exp + score + blockexp
                            p.sendMessage(bundle["exp.earn.defeat", score, (time + state.stats.enemyUnitsDestroyed + state.stats.unitsCreated + state.stats.buildingsBuilt) - (state.stats.buildingsDeconstructed + state.stats.buildingsDestroyed) + blockexp])
                        }
                        p.sendMessage(bundle["exp.current", target.exp, target.exp - oldExp, target.level, target.level - oldLevel])
                        database.update(p.uuid(), target)
                        p.sendMessage(bundle["data.saved"])
                    }
                }
            }
        }

        Events.on(BlockBuildBeginEvent::class.java) {

        }

        Events.on(BlockBuildEndEvent::class.java) {
            if (it.unit.isPlayer) {
                val player = findPlayerData(it.unit.player.uuid())
                if (player != null) {
                    if (!it.breaking) player.placecount++ else player.breakcount++
                }
            }

            val isDebug = Core.settings.getBool("debugMode")

            if (it.unit.isPlayer) {
                val player = it.unit.player
                val target = findPlayerData(player.uuid())

                if (!player.unit().isNull && target != null && it.tile.block() != null && player.unit().buildPlan() != null) {
                    val block = it.tile.block()
                    if (!it.breaking) {
                        log(LogType.Block, "${player.name} placed ${block.name}")
                        addLog(TileLog(System.currentTimeMillis(), player.name, "place", it.tile.x, it.tile.y, it.tile.block().name))
                        addLog(PlayerLog(player.name, it.tile.block(), it.tile.build.rotation, it.tile.x, it.tile.y, "place", it.unit.team, null))
                        target.placecount + 1
                        target.exp = target.exp + blockExp.get(block.name)

                        if (isDebug) {
                            Log.info("${player.name} placed ${it.tile.block().name} to ${it.tile.x},${it.tile.y}")
                        }
                    } else if (it.breaking) {
                        log(LogType.Block, "${player.name} break ${player.unit().buildPlan().block.name}")
                        addLog(TileLog(System.currentTimeMillis(), player.name, "break", it.tile.x, it.tile.y, player.unit().buildPlan().block.name))
                        addLog(PlayerLog(player.name, player.unit().buildPlan().block, if (player.unit().buildPlan().build() != null) player.unit().buildPlan().build().rotation else 0, it.tile.x, it.tile.y, "break", it.unit.team, null))
                        target.breakcount + 1
                        target.exp = target.exp - blockExp.get(player.unit().buildPlan().block.name)

                        if (isDebug) {
                            Log.info("${player.name} break ${player.unit().buildPlan().block.name} to ${it.tile.x},${it.tile.y}")
                        }
                    }
                }
            }
        }

        Events.on(BuildSelectEvent::class.java) {
            if (it.builder is Playerc && it.builder.buildPlan() != null && !Pattern.matches(".*build.*", it.builder.buildPlan().block.name) && it.tile.block() !== Blocks.air && it.breaking) {
                log(LogType.Block, "${(it.builder as Playerc).name()} remove ${it.tile.block().name} to ${it.tile.x},${it.tile.y}")
            }
        }

        Events.on(BlockDestroyEvent::class.java) {
            if (Config.destroyCore && state.rules.coreCapture) {
                Fx.spawnShockwave.at(it.tile.getX(), it.tile.getY(), state.rules.dropZoneRadius)
                Damage.damage(world.tile(it.tile.pos()).team(), it.tile.getX(), it.tile.getY(), state.rules.dropZoneRadius, 1.0E8f, true)
            }
        }

        Events.on(UnitDestroyEvent::class.java) {

        }

        Events.on(UnitCreateEvent::class.java) {
            if (Groups.unit.size() > Config.spawnLimit) {
                Groups.player.forEach {
                    val data = findPlayerData(it.uuid())
                    if (data != null) {
                        val bundle = Bundle(data.languageTag)
                        it.sendMessage(bundle["config.spawnlimit.reach", "[scarlet]${Groups.unit.size()}[white]/[sky]${Config.spawnLimit}"])
                    }
                }
            }
        }

        Events.on(UnitChangeEvent::class.java) {

        }

        Events.on(PlayerJoin::class.java) {
            log(LogType.Player, "${it.player.plainName()} (${it.player.uuid()}, ${it.player.con.address}) joined.")
            it.player.admin(false)

            if (!enemyCoresCounted && state.rules.attackMode) {
                enemyCores = max(state.teams.present.sum { t -> if (t.team !== it.player.team()) t.cores.size else 0 }, 1)
                enemyCoresCounted = true
            }

            if (Config.authType == Config.AuthType.None) {
                val data = database[it.player.uuid()]
                if (data != null) {
                    Trigger.loadPlayer(it.player, data)
                } else if (Config.authType != Config.AuthType.None) {
                    it.player.sendMessage("[green]To play the server, use the [scarlet]/reg[] command to register account.")
                } else if (Config.authType == Config.AuthType.None) {
                    Trigger.createPlayer(it.player, null, null)
                }
            }
        }

        Events.on(PlayerLeave::class.java) {
            for (a in 0..players.size()) {
                if (players.get(a).asObject().get("uuid").asString().equals(it.player.uuid())) {
                    players.remove(a)
                    break
                }
            }
            log(LogType.Player, "${it.player.plainName()} (${it.player.uuid()}, ${it.player.con.address}) disconnected.")
            val data = database.players.find { data -> data.uuid == it.player.uuid() }
            if (data != null) {
                database.update(it.player.uuid(), data)
            }
            database.players.remove(data)
        }

        Events.on(PlayerBanEvent::class.java) {
            if (Config.blockIP) {
                val os = System.getProperty("os.name").lowercase(Locale.getDefault())
                if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
                    val ip = if (it.player != null) it.player.ip() else netServer.admins.getInfo(it.uuid).lastIP
                    val cmd = if (it.player != null) {
                        arrayOf("/bin/bash", "-c", "echo ${PluginData.sudoPassword} | sudo -S iptables -A INPUT -s $ip -j DROP")
                    } else {
                        arrayOf("/bin/bash", "-c", "echo ${PluginData.sudoPassword} | sudo -S iptables -A INPUT -s $ip -j DROP")
                    }
                    Runtime.getRuntime().exec(cmd)
                    Log.info(Bundle()["event.ban.iptables", ip])
                }
            }
            log(LogType.Player, Bundle()["log.player.banned", if (it.player == null) netServer.admins.getInfo(it.uuid).lastName else it.player.name, if (it.player == null) netServer.admins.getInfo(it.uuid).lastIP else it.player.ip()])
        }

        Events.on(WorldLoadEvent::class.java) {
            PluginData.playtime = 0L
            enemyCoresCounted = false
            if (state.rules.pvp && Config.pvpPeace) {
                orignalBlockMultiplier = state.rules.blockDamageMultiplier
                orignalUnitMultiplier = state.rules.unitDamageMultiplier
                state.rules.blockDamageMultiplier = 0f
                state.rules.unitDamageMultiplier = 0f
            }
        }

        Events.on(PlayerConnect::class.java) { e ->
            log(LogType.Player, "${e.player.plainName()} (${e.player.uuid()}, ${e.player.con.address}) connected.")

            // 닉네임이 블랙리스트에 등록되어 있는지 확인
            for (s in PluginData.blacklist) {
                if (e.player.name.matches(Regex(s))) Call.kick(e.player.con, "This name is blacklisted.")
            }

            if (Config.fixedName) {
                if (e.player.name.length > 32) Call.kick(e.player.con(), "Nickname too long!")
                if (e.player.name.matches(Regex(".*\\[.*].*"))) Call.kick(e.player.con(), "Parentheses aren't allowed in nickname.")
                if (e.player.name.contains("　")) Call.kick(e.player.con(), "Don't use blank speical charactor nickname!")
                if (e.player.name.contains(" ")) Call.kick(e.player.con(), "Nicknames can't be used on this server!")
            }

            if (Config.minimalName && e.player.name.length < 4) Call.kick(e.player.con(), "Nickname too short!")

            if (Config.antiVPN) {
                val br = BufferedReader(InputStreamReader(Main::class.java.classLoader.getResourceAsStream("IP2LOCATION-LITE-DB1.BIN")!!))
                br.use { _ ->
                    var line: String
                    while (br.readLine().also { line = it } != null) {
                        val match = IpAddressMatcher(line)
                        if (match.matches(e.player.con.address)) {
                            Call.kick(e.player.con(), Bundle()["anti-grief.vpn"])
                        }
                    }
                }
            }
        }

        Events.on(MenuOptionChooseEvent::class.java) {
            if (it.menuId == 0 && it.option == 0) {
                val d = findPlayerData(it.player.uuid())
                if (d != null) {
                    d.languageTag = "ko"
                    it.player.sendMessage(Bundle(d.languageTag)["command.language.preview", Locale(d.languageTag).toLanguageTag()])
                }
            }
        }

        Events.run(EventType.Trigger.impactPower) {

        }

        fun send(message: String, vararg parameter: Any) {
            Groups.player.forEach {
                val data = findPlayerData(it.uuid())
                if (data != null) {
                    if (voteTargetUUID != data.uuid) {
                        val bundle = Bundle(data.languageTag)
                        Core.app.post { it.sendMessage(bundle.get(message, *parameter)) }
                    }
                }
            }
        }

        fun check(): Int {
            return when (database.players.size) {
                1 -> 1
                in 2..4 -> 2
                in 5..6 -> 3
                7 -> 4
                in 8..9 -> 5
                in 10..11 -> 6
                12 -> 7
                else -> 8
            }
        }

        fun back(map: Map?) {
            Core.app.post {
                val savePath: Fi = saveDirectory.child("rollback.msav")

                try {
                    val mode = state.rules.mode()
                    val reloader = WorldReloader()

                    reloader.begin()

                    if (map != null) {
                        world.loadMap(map, map.applyRules(mode))
                    } else {
                        SaveIO.load(savePath)
                    }

                    state.rules = state.map.applyRules(mode)

                    logic.play()
                    reloader.end()
                } catch (t: Exception) {
                    t.printStackTrace()
                }
                send("command.vote.back.done")
            }
        }

        var colorOffset = 0
        fun nickcolor(name: String, player: Playerc) {
            val stringBuilder = StringBuilder()
            val colors = arrayOfNulls<String>(11)
            colors[0] = "[#ff0000]"
            colors[1] = "[#ff7f00]"
            colors[2] = "[#ffff00]"
            colors[3] = "[#7fff00]"
            colors[4] = "[#00ff00]"
            colors[5] = "[#00ff7f]"
            colors[6] = "[#00ffff]"
            colors[7] = "[#007fff]"
            colors[8] = "[#0000ff]"
            colors[9] = "[#8000ff]"
            colors[10] = "[#ff00ff]"
            val newName = arrayOfNulls<String>(name.length)
            for (i in name.indices) {
                val c = name[i]
                var colorIndex = (i + colorOffset) % colors.size
                if (colorIndex < 0) {
                    colorIndex += colors.size
                }
                val newtext = colors[colorIndex] + c
                newName[i] = newtext
            }
            colorOffset--
            for (s in newName) {
                stringBuilder.append(s)
            }
            player.name(stringBuilder.toString())
        }

        var secondCount = 0
        var minuteCount = 0
        var count = 60

        var rollbackCount = Config.rollbackTime
        var messageCount = Config.messageTime
        var messageOrder = 0

        Events.run(EventType.Trigger.update) {
            for (a in database.players) {
                if (a.status.containsKey("freeze")) {
                    val player = findPlayers(a.uuid)
                    if (player != null) {
                        val split = a.status.get("freeze").toString().split("/")
                        player.set(split[0].toFloat(), split[1].toFloat())
                        Call.setPosition(player.con(), split[0].toFloat(), split[1].toFloat())
                        Call.setCameraPosition(player.con(), split[0].toFloat(), split[1].toFloat())
                    }
                }

                if (a.status.containsKey("tracking")) {
                    for (b in Groups.player) {
                        Call.label(a.player.con(), b.name, Time.delta / 2, b.mouseX, b.mouseY)
                    }
                }

                if (a.status.containsKey("tpp")) {
                    val data = a.status.get("tpp")
                    val target = Groups.player.find { p -> p.uuid() == data }
                    if (target != null) {
                        Call.setCameraPosition(a.player.con(), target.x, target.y)
                    } else {
                        a.status.remove("tpp")
                        Call.setCameraPosition(a.player.con(), a.player.x, a.player.y)
                    }
                }
            }

            if (secondCount == 60) {
                PluginData.uptime++
                PluginData.playtime++

                for (a in database.players) {
                    a.playtime = a.playtime + 1

                    if (a.colornick) {
                        val name = a.name.replace("\\[(.*?)]".toRegex(), "")
                        nickcolor(name, a.player)
                    } else {
                        a.player.name(a.name)
                    }

                    // 잠수 플레이어 카운트
                    if (a.x == a.player.tileX() && a.y == a.player.tileY()) {
                        a.afkTime++
                        if (a.afkTime == Config.afkTime) {
                            a.player.kick("AFK")
                        }
                    } else {
                        a.afkTime = 0
                    }

                    a.exp = a.exp + random.nextInt(7)
                    Commands.Exp[a]

                    if (Config.expDisplay) {
                        val message = "${a.exp}/${floor(Commands.Exp.calculateFullTargetXp(a.level)).toInt()}"

                        Call.infoPopup(a.player.con(), message, Time.delta, Align.left, 0, 0, 300, 0)
                    }

                }

                if (voting) {
                    if (count % 10 == 0) {
                        send("command.vote.count", count.toString(), check() - voted.size)
                        if (voteType == "kick" && Groups.player.find { a -> a.uuid() == voteTargetUUID } == null) {
                            send("command.vote.kick.target.leave")

                        }
                    }
                    count--
                    if ((count == 0 && check() <= voted.size) || check() <= voted.size || isAdminVote) {
                        send("command.vote.success")

                        when (voteType) {
                            "kick" -> {
                                val name = netServer.admins.getInfo(voteTargetUUID).lastName
                                if (Groups.player.find { a -> a.uuid() == voteTargetUUID } == null) {
                                    netServer.admins.banPlayerID(voteTargetUUID)
                                    send("command.vote.kick.target.banned", name)
                                } else {
                                    voteTarget?.kick(Packets.KickReason.kick, 60 * 60 * 1000)
                                    send("command.vote.kick.target.kicked", name)
                                }
                            }

                            "map" -> {
                                back(voteMap)
                            }

                            "gg" -> {
                                Events.fire(GameOverEvent(state.rules.waveTeam))
                            }

                            "skip" -> {
                                for (a in 0..voteWave!!) logic.runWave()
                                send("command.vote.skip.done", voteWave!!.toString())
                            }

                            "back" -> {
                                back(null)
                            }

                            "random" -> {
                                if (lastVoted.plusMinutes(10).isBefore(LocalTime.now())) {
                                    send("command.vote.random.cool")
                                } else {
                                    lastVoted = LocalTime.now()
                                    send("command.vote.random.done")
                                    Thread {
                                        val map: Map
                                        val random = Random()
                                        send("command.vote.random.is")
                                        Thread.sleep(3000)
                                        when (random.nextInt(7)) {
                                            0 -> {
                                                send("command.vote.random.unit")
                                                Groups.unit.each {
                                                    if (voteStarter != null) {
                                                        if (it.team == voteStarter!!.team()) it.kill()
                                                    } else {
                                                        it.kill()
                                                    }
                                                }
                                                send("command.vote.random.unit.wave")
                                                logic.runWave()
                                            }

                                            1 -> {
                                                send("command.vote.random.wave")
                                                for (a in 0..5) logic.runWave()
                                            }

                                            2 -> {
                                                send("command.vote.random.health")
                                                Groups.build.each {
                                                    if (voteStarter != null) {
                                                        if (it.team == voteStarter!!.team()) {
                                                            it.block.health = it.block.health / 2
                                                        }
                                                    } else {
                                                        it.block.health = it.block.health / 2
                                                    }
                                                }
                                                for (a in Groups.player) {
                                                    Call.worldDataBegin(a.con)
                                                    netServer.sendWorldData(a)
                                                }
                                            }

                                            3 -> {
                                                send("command.vote.random.fill.core")
                                                if (voteStarter != null) {
                                                    for (item in content.items()) {
                                                        state.teams.cores(voteStarter!!.team()).first().items.add(item, Random(516).nextInt(500))
                                                    }
                                                } else {
                                                    for (item in content.items()) {
                                                        state.teams.cores(Team.sharded).first().items.add(item, Random(516).nextInt(500))
                                                    }
                                                }
                                            }

                                            4 -> {
                                                send("command.vote.random.storm")
                                                Thread.sleep(1000)
                                                Call.createWeather(Weathers.rain, 10f, 60 * 60f, 50f, 10f)
                                            }

                                            5 -> {
                                                send("command.vote.random.fire")
                                                for (x in 0 until world.width()) {
                                                    for (y in 0 until world.height()) {
                                                        Call.effect(Fx.fire, (x * 8).toFloat(), (y * 8).toFloat(), 0f, Color.red)
                                                    }
                                                }
                                                var tick = 600
                                                map = state.map

                                                while (tick != 0 && map == state.map) {
                                                    Thread.sleep(1000)
                                                    tick--
                                                    Core.app.post {
                                                        Groups.unit.each {
                                                            it.health(it.health() - 10f)
                                                        }
                                                        Groups.build.each {
                                                            it.block.health = it.block.health / 30
                                                        }
                                                    }
                                                    if (tick == 300) {
                                                        send("command.vote.random.supply")
                                                        repeat(2) {
                                                            if (voteStarter != null) {
                                                                UnitTypes.oct.spawn(voteStarter!!.team(), voteStarter!!.x, voteStarter!!.y)
                                                            } else {
                                                                UnitTypes.oct.spawn(Team.sharded, state.teams.cores(Team.sharded).first().x, state.teams.cores(Team.sharded).first().y)
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            else -> {
                                                send("command.vote.random.nothing")
                                            }
                                        }
                                    }.start()
                                }
                            }
                        }

                        voting = false
                        voteType = null
                        voteTarget = null
                        voteTargetUUID = null
                        voteReason = null
                        voteMap = null
                        voteWave = null
                        voteStarter = null
                        isCanceled = false
                        isAdminVote = false
                        voted = Seq<String>()
                        count = 60
                    } else if ((count == 0 && check() > voted.size) || isCanceled) {
                        send("command.vote.failed")

                        voting = false
                        voteType = null
                        voteTarget = null
                        voteTargetUUID = null
                        voteReason = null
                        voteMap = null
                        voteWave = null
                        voteStarter = null
                        isCanceled = false
                        isAdminVote = false
                        voted = Seq<String>()
                        count = 60
                    }
                }

                if (Config.pvpPeace) {
                    if (Trigger.pvpCount != 0) {
                        Trigger.pvpCount--
                    } else {
                        state.rules.blockDamageMultiplier = orignalBlockMultiplier
                        state.rules.unitDamageMultiplier = orignalUnitMultiplier
                        send("trigger.pvp.end")
                    }
                }

                PluginData.save()

                secondCount = 0
            } else {
                secondCount++
            }

            if (minuteCount == 3600) {
                val data = database.getAll()

                for (a in data) {
                    if (a.status.containsKey("ban") && LocalDateTime.now().isAfter(LocalDateTime.parse(a.status.get("ban")))) {
                        netServer.admins.unbanPlayerID(a.uuid)
                    }
                }

                if (rollbackCount == 0) {
                    Core.app.post { SaveIO.save(saveDirectory.child("rollback.msav")) }
                    rollbackCount = Config.rollbackTime
                } else {
                    rollbackCount--
                }

                if (Config.message) {
                    if (messageCount == Config.messageTime) {
                        for (a in database.players) {
                            val message = if (Main.root.child("messages/${a.languageTag}.txt").exists()) {
                                Main.root.child("messages/${a.languageTag}.txt").readString()
                            } else {
                                val file = Main.root.child("messages/en.txt")
                                if (file.exists()) file.readString() else ""
                            }
                            val c = message.split(Regex("\r\n"))

                            if (c.size <= messageOrder) {
                                messageOrder = 0
                            }
                            a.player.sendMessage(c[messageOrder])

                        }
                        messageOrder++
                        messageCount = 0
                    } else {
                        messageCount++
                    }
                }
                minuteCount = 0
            } else {
                minuteCount++
            }
        }
    }

    fun log(type: LogType, text: String, vararg name: String) {
        val root: Fi = Core.settings.dataDirectory.child("mods/Essentials/")
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        if (type != LogType.Report) {
            val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH_mm_ss").format(LocalDateTime.now())
            val new = Paths.get(root.child("log/$type.log").path())
            val old = Paths.get(root.child("log/old/$type/$date.log").path())
            var main = root.child("log/$type.log")
            val folder = root.child("log")

            if (main != null && main.length() > 2048 * 256) {
                main.writeString("end of file. $date", true)
                try {
                    if (!root.child("log/old/$type").exists()) {
                        root.child("log/old/$type").mkdirs()
                    }
                    Files.move(new, old, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                main = null
            }
            if (main == null) main = folder.child("$type.log")
            main!!.writeString("[$time] $text\n", true)
        } else {
            val main = root.child("log/report/$time $name.txt")
            main.writeString(text)
        }
    }

    enum class LogType {
        Player, Tap, WithDraw, Block, Deposit, Chat, Report
    }

    class IpAddressMatcher(ipAddress: String) {
        private var nMaskBits = 0
        private val requiredAddress: InetAddress
        fun matches(address: String): Boolean {
            val remoteAddress = parseAddress(address)
            if (requiredAddress.javaClass != remoteAddress.javaClass) {
                return false
            }
            if (nMaskBits < 0) {
                return remoteAddress == requiredAddress
            }
            val remAddr = remoteAddress.address
            val reqAddr = requiredAddress.address
            val nMaskFullBytes = nMaskBits / 8
            val finalByte = (0xFF00 shr (nMaskBits and 0x07)).toByte()
            for (i in 0 until nMaskFullBytes) {
                if (remAddr[i] != reqAddr[i]) {
                    return false
                }
            }
            return if (finalByte.toInt() != 0) {
                remAddr[nMaskFullBytes] and finalByte == reqAddr[nMaskFullBytes] and finalByte
            } else true
        }

        private fun parseAddress(address: String): InetAddress {
            return try {
                InetAddress.getByName(address)
            } catch (e: UnknownHostException) {
                throw IllegalArgumentException("Failed to parse address$address", e)
            }
        }

        init {
            var address = ipAddress
            if (address.indexOf('/') > 0) {
                val addressAndMask = address.split("/").toTypedArray()
                address = addressAndMask[0]
                nMaskBits = addressAndMask[1].toInt()
            } else {
                nMaskBits = -1
            }
            requiredAddress = parseAddress(address)
            assert(requiredAddress.address.size * 8 >= nMaskBits) {
                String.format("IP address %s is too short for bitmask of length %d", address, nMaskBits)
            }
        }
    }

    fun findPlayerData(uuid: String): DB.PlayerData? {
        return database.players.find { e -> e.uuid == uuid }
    }

    fun findPlayers(name: String): Playerc? {
        return if (name.toIntOrNull() != null) {
            val d = players.find { it.asObject().get("id").asInt() == name.toInt() }
            if (d != null) {
                Groups.player.find { p -> p.uuid() == d.asObject().get("uuid").asString() }
            } else {
                null
            }
        } else {
            Groups.player.find { p -> p.name.contains(name, true) }
        }
    }

    fun addLog(log: TileLog) {
        worldHistory.add(log)
    }

    fun addLog(log: PlayerLog) {
        playerHistory.add(log)
    }

    class TileLog(val time: Long, val player: String, val action: String, val x: Short, val y: Short, val tile: String)
    class PlayerLog(val player: String, val block: Block, val rotate: Int, val x: Short, val y: Short, val action: String, val team: Team, val config: ObjectMap<Class<*>, Cons2<Any, Any>>?)
}