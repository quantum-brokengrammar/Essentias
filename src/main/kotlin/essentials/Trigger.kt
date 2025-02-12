package essentials

import arc.Core
import arc.func.Prov
import arc.struct.ArrayMap
import arc.struct.Seq
import arc.util.Log
import arc.util.Time
import com.ip2location.IP2Location
import com.neovisionaries.i18n.CountryCode
import essentials.Main.Companion.database
import essentials.Main.Companion.root
import mindustry.Vars.state
import mindustry.Vars.world
import mindustry.content.Blocks
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.Playerc
import mindustry.net.Host
import mindustry.net.NetworkIO.readServerData
import org.hjson.JsonObject
import org.mindrot.jbcrypt.BCrypt
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.*
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.concurrent.thread


object Trigger {
    val ip2location = IP2Location()
    var pvpCount = Config.pvpPeaceTime

    init {
        Main::class.java.classLoader.getResourceAsStream("IP2LOCATION-LITE-DB1.BIN")?.run {
            ip2location.Open(this.readBytes())
        }
    }

    fun loadPlayer(player: Playerc, data: DB.PlayerData) {
        player.name(data.name)
        data.lastdate = System.currentTimeMillis()
        data.joincount = data.joincount++
        data.player = player

        val perm = Permission[player]
        if (perm.name.isNotEmpty()) player.name(Permission[player].name)
        player.admin(Permission[player].admin)
        player.sendMessage(Bundle(data.languageTag)["event.player.loaded"])

        database.players.add(data)

        val ee = JsonObject()
        ee.add("id", Event.order)
        ee.add("name", data.name)
        ee.add("uuid", player.uuid())

        Event.players.add(ee)
        Event.order++

        val motd = if (root.child("motd/${data.languageTag}.txt").exists()) {
            root.child("motd/${data.languageTag}.txt").readString()
        } else {
            val file = root.child("motd/en.txt")
            if (file.exists()) file.readString() else ""
        }
        val count = motd.split("\r\n|\r|\n").toTypedArray().size
        if (count > 10) Call.infoMessage(player.con(), motd) else player.sendMessage(motd)

        if (perm.isAlert) {
            if (perm.alertMessage.isEmpty()) {
                for (a in database.players) {
                    a.player.sendMessage(Bundle(a.languageTag)["event.player.joined", player.name()])
                }
            } else {
                Call.sendMessage(perm.alertMessage)
            }
        }
    }

    fun createPlayer(player: Playerc, id: String?, password: String?) {
        val data = DB.PlayerData()

        data.name = player.name()
        data.uuid = player.uuid()
        data.joinDate = System.currentTimeMillis()
        data.id = id ?: player.name()
        data.pw = if (password == null) player.name() else BCrypt.hashpw(password, BCrypt.gensalt())
        data.permission = "user"

        database.createData(data)
        Permission.apply()

        player.sendMessage("Player data registered!")
        loadPlayer(player, data)

        Thread {
            val ip = player.ip()
            val isLocal = try {
                val address = InetAddress.getByName(ip)
                if (address.isAnyLocalAddress || address.isLoopbackAddress) {
                    true
                } else {
                    NetworkInterface.getByInetAddress(address) != null
                }
            } catch (e: SocketException) {
                false
            } catch (e: UnknownHostException) {
                false
            }

            val res = if (isLocal) {
                val add = BufferedReader(InputStreamReader(URL("http://checkip.amazonaws.com").openStream())).readLine()
                ip2location.IPQuery(add).countryShort
            } else {
                ip2location.IPQuery(player.ip()).countryShort
            }

            val locale = if (CountryCode.getByCode(res) == null) {
                Locale.ENGLISH
            } else {
                CountryCode.getByCode(res).toLocale()
            }

            data.languageTag = locale.toLanguageTag()
        }.start()

        Call.menu(player.con(), 0, "Select your language", "Select plugin language\n플러그인 언어를 선택하세요.", arrayOf(arrayOf("한국어", "English")))
    }

    class Thread : Runnable {
        private var ping = 0.000
        private val servers = ArrayMap<String, Int>()
        private val dummy = Player.create()

        override fun run() {
            while (!java.lang.Thread.currentThread().isInterrupted) {
                try {
                    if (state.isPlaying) {
                        for (i in 0 until PluginData.warpCounts.size) {
                            val value = PluginData.warpCounts[i]
                            pingHostImpl(value.ip, value.port) { r: Host ->
                                if (r.name != null) {
                                    ping += ("0." + r.ping).toDouble()
                                    val str = r.players.toString()
                                    val digits = IntArray(str.length)
                                    for (a in str.indices) digits[a] = str[a] - '0'
                                    val tile = value.tile
                                    if (value.players != r.players) {
                                        for (px in 0..2) {
                                            for (py in 0..4) {
                                                Call.deconstructFinish(world.tile(tile.x + 4 + px, tile.y + py), Blocks.air, dummy.unit())
                                            }
                                        }
                                    }
                                    dummy.x = tile.getX()
                                    dummy.y = tile.getY()

                                    Commands.Client(arrayOf(str), dummy).chars(tile) // i 번째 server ip, 포트, x좌표, y좌표, 플레이어 인원, 플레이어 인원 길이
                                    PluginData.warpCounts[i] = PluginData.WarpCount(state.map.name(), value.tile.pos(), value.ip, value.port, r.players, digits.size)
                                    addPlayers(value.ip, value.port, r.players)
                                } else {
                                    ping += 1.000

                                    dummy.x = value.tile.getX()
                                    dummy.y = value.tile.getY()
                                    Commands.Client(arrayOf("no"), dummy).chars(value.tile)
                                }
                            }
                        }

                        val memory = Seq<String>()
                        for (value in PluginData.warpBlocks) {
                            val tile = world.tile(value.x, value.y)
                            if (tile.block() == Blocks.air) {
                                PluginData.warpBlocks.remove(value)
                            } else {
                                var margin = 0f
                                var isDup = false
                                val x = tile.build.getX()

                                when (value.size) {
                                    1 -> margin = 8f
                                    2 -> {
                                        margin = 16f
                                        isDup = true
                                    }

                                    3 -> margin = 16f
                                    4 -> {
                                        margin = 24f
                                        isDup = true
                                    }

                                    5 -> margin = 24f
                                    6 -> {
                                        margin = 32f
                                        isDup = true
                                    }

                                    7 -> margin = 32f
                                }

                                var y = tile.build.getY() + if (isDup) margin - 8 else margin
                                var players = 0

                                try {
                                    pingHostImpl(value.ip, value.port) { r: Host ->
                                        ping += ("0." + r.ping).toDouble()
                                        if (isDup) y += 4
                                        memory.add("[yellow]" + r.players + "[] Players///" + x + "///" + y)
                                        value.online = true
                                        players = r.players
                                    }
                                } catch (e: IOException) {
                                    ping += 1.000
                                    memory.add("[scarlet]Offline///$x///$y")
                                    value.online = false
                                }

                                if (isDup) margin -= 4
                                memory.add(value.description + "///" + x + "///" + (tile.build.getY() - margin))
                                addPlayers(value.ip, value.port, players)
                            }
                        }
                        for (m in memory) {
                            val a = m.split("///").toTypedArray()
                            Call.label(a[0], ping.toFloat() + 3f, a[1].toFloat(), a[2].toFloat())
                        }

                        for (i in 0 until PluginData.warpTotals.size) {
                            val value = PluginData.warpTotals[i]
                            if (state.map.name() == value.mapName) {
                                if (value.totalplayers != totalPlayers()) {
                                    // todo 버그 수정
                                    when (totalPlayers()) {
                                        0, 1, 2, 3, 4, 5, 6, 7, 8, 9 -> {
                                            for (px in 0..2) {
                                                for (py in 0..4) {
                                                    Call.setTile(world.tile(value.tile.x + px, value.tile.y + py), Blocks.air, Team.sharded, 0)
                                                }
                                            }
                                        }

                                        else -> {
                                            for (px in 0..5) {
                                                for (py in 0..4) {
                                                    Call.setTile(world.tile(value.tile.x + 4 + px, value.tile.y + py), Blocks.air, Team.sharded, 0)
                                                }
                                            }
                                        }
                                    }
                                }

                                dummy.x = value.tile.getX()
                                dummy.y = value.tile.getY()
                                Commands.Client(arrayOf(totalPlayers().toString()), dummy).chars(value.tile)
                            }
                        }

                        if (Config.countAllServers) {
                            Core.settings.put("totalPlayers", totalPlayers() + Groups.player.size())
                            Core.settings.saveValues()
                        }
                        ping = 0.000
                    }
                    TimeUnit.SECONDS.sleep(3)
                } catch (e: Exception) {
                    java.lang.Thread.currentThread().interrupt()
                }
            }
        }

        @Throws(IOException::class, SocketException::class)
        private fun pingHostImpl(address: String, port: Int, listener: Consumer<Host>) {
            val packetSupplier: Prov<DatagramPacket> = Prov<DatagramPacket> { DatagramPacket(ByteArray(512), 512) }

            DatagramSocket().use { socket ->
                val seconds: Long = Time.millis()
                socket.send(DatagramPacket(byteArrayOf(-2, 1), 2, InetAddress.getByName(address), port))
                socket.soTimeout = 2000
                val packet: DatagramPacket = packetSupplier.get()
                socket.receive(packet)
                val buffer = ByteBuffer.wrap(packet.data)
                val host = readServerData(Time.timeSinceMillis(seconds).toInt(), packet.address.hostAddress, buffer)
                host.port = port
                listener.accept(host)
            }
        }


        private fun addPlayers(ip: String?, port: Int, players: Int) {
            val mip = "$ip:$port"
            if (!servers.containsKey(mip)) {
                servers.put(mip, players)
            }
        }

        private fun totalPlayers(): Int {
            var total = 0
            for (v in servers) {
                total += v.value
            }
            return total
        }
    }

    class Server {
        val server = ServerSocket(6000)

        init {
            while (true) {
                val client = server.accept()
                thread { Handler(client).run() }
            }
        }

        class Handler(val client: Socket) {
            val reader = Scanner(client.getInputStream())
            val writer = client.getOutputStream()
            var run = false

            fun run() {
                run = true

                while (run) {
                    try {
                        when (reader.nextLine()) { // Client 에게 데이터 전달 준비
                            "send" -> {
                                write("ok")
                                val data = reader.nextLine()
                                println("[SERVER] data received: message is $data")
                            } // Client 에게서 오는 데이터 수신
                            "receive" -> { //val data = netServer.admins.banned.toString("&&")
                                write("send dummy data")
                                println("[SERVER] dummy data send.")
                            }

                            "crash" -> {
                                if (System.getenv("DEBUG_KEY") != null) {
                                    write("ok")
                                    val stacktrace = StringBuffer()
                                    while (reader.hasNextLine()) {
                                        stacktrace.append(reader.nextLine() + "\n")
                                    }
                                    root.child("report/${LocalDateTime.now().withNano(0)}.txt")
                                    write("done")
                                    Log.info("Crash log received from ${client.inetAddress.hostAddress}")
                                }
                            }

                            "exit" -> {
                                shutdown()
                            }
                        }
                    } catch (e: NoSuchElementException) {
                        shutdown()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        shutdown()
                    }
                }
            }

            private fun write(msg: String) {
                writer.write((msg + '\n').toByteArray(Charset.forName("UTF-8")))
            }

            private fun shutdown() {
                run = false
                client.close()
            }
        }
    }

    class Client {
        val address = Config.shareBanListServer
        val port = 6000

        val client = Handler(address, port)

        class Handler(address: String, port: Int) {
            val socket = Socket()
            var connected = false

            init {
                try {
                    socket.connect(InetSocketAddress(address, port), 5000)
                    connected = true
                    Log.info("You're connected to server.")
                } catch (e: SocketTimeoutException) {
                    Log.info("Connection timed out.")
                }
            }

            val reader = Scanner(socket.getInputStream())
            val writer = socket.getOutputStream()

            fun send(command: String, vararg parameter: String) {
                if (connected) {
                    when (command) {
                        "send" -> {
                            write("send")
                            reader.nextLine()
                            write("client sent data to server")
                            println("[CLIENT] send data to server")
                        }

                        "receive" -> {
                            write("receive")
                            val data = reader.nextLine()
                            println("[CLIENT] $data")
                        }

                        "crash" -> {
                            try {
                                Socket("mindustry.kr", 6000).use {
                                    it.soTimeout = 5000
                                    socket.getOutputStream().use { out ->
                                        out.write("crash\n".toByteArray(Charset.forName("UTF-8")))
                                        Scanner(socket.getInputStream()).use { sc ->
                                            sc.nextLine() // ok
                                            out.write("${parameter[0]}\n".toByteArray(Charset.forName("UTF-8")))
                                            sc.nextLine()
                                            Log.info("Crash log reported!")
                                        }
                                    }
                                }
                            } catch (e: SocketTimeoutException) {
                                Log.info("Connection timed out. crash report server may be closed.")
                            }
                        }

                        "exit" -> {
                            write("exit")
                            reader.close()
                            socket.close()
                        }
                    }
                }
            }

            private fun write(msg: String) {
                writer.write((msg + '\n').toByteArray(Charset.forName("UTF-8")))
            }
        }
    }
}