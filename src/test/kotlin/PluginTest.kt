
import arc.ApplicationCore
import arc.Core
import arc.Events
import arc.Settings
import arc.backend.headless.HeadlessApplication
import arc.files.Fi
import arc.graphics.Color
import arc.util.CommandHandler
import arc.util.Log
import com.github.javafaker.Faker
import essentials.Config
import essentials.Main
import essentials.Main.Companion.root
import junit.framework.TestCase.assertNotNull
import mindustry.Vars
import mindustry.Vars.netServer
import mindustry.Vars.world
import mindustry.content.Blocks
import mindustry.content.Items
import mindustry.content.UnitTypes
import mindustry.core.*
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.gen.Playerc
import mindustry.maps.Map
import mindustry.net.Net
import mindustry.net.NetConnection
import mindustry.world.Tile
import org.junit.AfterClass
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.lang.Thread.sleep
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.zip.ZipFile
import javax.sound.midi.MidiSystem
import kotlin.io.path.Path
import kotlin.math.min
import kotlin.math.pow


class PluginTest {
    companion object {
        private lateinit var main: Main
        private val r = Random()
        private lateinit var player: Playerc
        private lateinit var path: Fi
        private val serverCommand: CommandHandler = CommandHandler("")
        private val clientCommand: CommandHandler = CommandHandler("/")

        @BeforeClass
        @JvmStatic
        fun init() {
            if (System.getProperty("os.name").contains("Windows")) {
                val pathToBeDeleted: Path = Path("${System.getenv("AppData")}\\app").resolve("mods")
                if (File("${System.getenv("AppData")}\\app\\mods").exists()) {
                    Files.walk(pathToBeDeleted).sorted(Comparator.reverseOrder()).map { obj: Path -> obj.toFile() }.forEach { obj: File -> obj.delete() }
                }
            }

            Core.settings = Settings()
            Core.settings.dataDirectory = Fi("")
            path = Core.settings.dataDirectory

            path.child("locales").writeString("en")
            path.child("version.properties").writeString("modifier=release\ntype=official\nnumber=7\nbuild=custom build")

            if(!path.child("maps").exists()) {
                path.child("maps").mkdirs()

                ZipFile(Paths.get("src", "test", "resources", "maps.zip").toFile().absolutePath).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        if(entry.isDirectory) {
                            File(path.child("maps").absolutePath(), entry.name).mkdirs()
                        } else {
                            zip.getInputStream(entry).use { input ->
                                File(path.child("maps").absolutePath(), entry.name).outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
            }

            val testMap = arrayOfNulls<Map>(1)
            try {
                val begins = booleanArrayOf(false)
                val exceptionThrown = arrayOf<Throwable?>(null)
                val core: ApplicationCore = object : ApplicationCore() {
                    override fun setup() {
                        Vars.headless = true
                        Vars.net = Net(null)
                        Vars.tree = FileTree()
                        Vars.init()
                        Vars.content.createBaseContent()
                        add(Logic().also { Vars.logic = it })
                        add(NetServer().also { netServer = it })
                        Vars.content.init()
                    }

                    override fun init() {
                        super.init()
                        begins[0] = true
                        testMap[0] = Vars.maps.loadInternalMap("maze")
                        Thread.currentThread().interrupt()
                    }
                }
                HeadlessApplication(core, 60f) { throwable: Throwable? -> exceptionThrown[0] = throwable }
                while(!begins[0]) {
                    if(exceptionThrown[0] != null) {
                        exceptionThrown[0]!!.printStackTrace()
                        Assert.fail()
                    }
                    sleep(10)
                }
            } catch(e: Exception) {
                e.printStackTrace()
            }

            Groups.init()
            world.loadMap(testMap[0])
            Vars.state.set(GameState.State.playing)
            Version.build = 128
            Version.revision = 0

            path.child("locales").delete()
            path.child("version.properties").delete()

            Core.settings.put("debugMode", true)

            main = Main()
            main.init()
            main.registerClientCommands(clientCommand)
            main.registerServerCommands(serverCommand)

            // 플레이어 생성
            player = createPlayer()

            // Call 오류 해결
            Vars.player = player as Player
            Vars.netClient = NetClient()
        }

        @AfterClass
        @JvmStatic
        fun shutdown() {
            path.child("mods/Essentials").deleteDirectory()
            path.child("maps").deleteDirectory()
        }

        private fun getSaltString(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz1234567890"
            val salt = StringBuilder()
            while(salt.length < 25) {
                val index = (r.nextFloat() * chars.length).toInt()
                salt.append(chars[index])
            }
            return salt.toString()
        }

        private fun createPlayer(): Player {
            val player = Player.create()
            val faker = Faker.instance(Locale.ENGLISH)
            val ip = r.nextInt(255).toString() + "." + r.nextInt(255) + "." + r.nextInt(255) + "." + r.nextInt(255)

            player.reset()
            player.con = object : NetConnection(ip) {
                override fun send(`object`: Any?, reliable: Boolean) {
                    TODO("Not yet implemented")
                }

                override fun close() {
                    TODO("Not yet implemented")
                }
            }
            player.name(faker.name().lastName())
            player.con.uuid = getSaltString()
            player.con.usid = getSaltString()
            player.set(r.nextInt(300).toFloat(), r.nextInt(500).toFloat())
            player.color.set(Color.rgb(r.nextInt(255), r.nextInt(255), r.nextInt(255)))
            player.color.a = r.nextFloat()
            player.team(Team.sharded)
            player.unit(UnitTypes.dagger.spawn(r.nextInt(300).toFloat(), r.nextInt(500).toFloat()))
            player.add()
            netServer.admins.getInfo(player.uuid())
            netServer.admins.updatePlayerJoined(player.uuid(), player.con.address, player.name)
            Groups.player.update()

            assertNotNull(player)
            return player
        }
    }

    fun randomTile(): Tile {
        val random = Random()
        return world.tile(random.nextInt(100), random.nextInt(100))
    }

    @Test
    fun genDocs(){
        serverCommand.handleMessage("gen")
    }

    @Test
    fun test(){
        val random = Random()
        Events.fire(EventType.ServerLoadEvent())

        Events.fire(EventType.PlayerConnect(player.self()))
        Events.fire(EventType.PlayerJoin(player.self()))

        Config.authType = Config.AuthType.Password
        clientCommand.handleMessage("/reg testas test123 test123", player)
        sleep(300)
        clientCommand.handleMessage("/login testas test123", player)
        sleep(500)
        Main.database.players.find { e -> e.uuid == player.uuid() }.permission = "owner"

        // 더미 플레이어
        val dummy : Playerc = createPlayer()
        Events.fire(EventType.PlayerConnect(dummy.self()))
        Events.fire(EventType.PlayerJoin(dummy.self()))

        Events.fire(EventType.ConfigEvent(randomTile().build, player.self(), random.nextInt(5)))
        Events.fire(EventType.TapEvent(player.self(), randomTile()))
        Events.fire(EventType.WithdrawEvent(randomTile().build, player.self(), Items.coal, random.nextInt(30)))
        Events.fire(EventType.GameOverEvent(Team.sharded))

        player.unit().addItem(Items.copper, 30)
        //Events.fire(EventType.DepositEvent(randomTile().build, player.self(), Items.copper, 30))
        Events.fire(EventType.PlayerChatEvent(player.self(), "안녕"))
        Events.fire(EventType.BlockBuildEndEvent(randomTile(), player.unit(), Team.sharded, false, null))
        Events.fire(EventType.BlockBuildEndEvent(randomTile(), player.unit(), Team.sharded, true, null))
        Events.fire(EventType.BuildSelectEvent(randomTile(), Team.crux, player.unit(), false))
        Events.fire(EventType.BuildSelectEvent(randomTile(), Team.crux, player.unit(), true))

        // 플레이어 설정
        clientCommand.handleMessage("/chars abcdefghijklmnopqrstuvwxyz1234567890", player)

        clientCommand.handleMessage("/color", player)
        sleep(3000)
        clientCommand.handleMessage("/color", player)

        clientCommand.handleMessage("/effect", player)

        clientCommand.handleMessage("/gg 10", player)

        repeat(2) { clientCommand.handleMessage("/god ${player.name()}", player) }

        clientCommand.handleMessage("/help aa", player)
        clientCommand.handleMessage("/help", player)
        clientCommand.handleMessage("/help 99", player)

        clientCommand.handleMessage("/hub", player)
        clientCommand.handleMessage("/hub zone", player)
        clientCommand.handleMessage("/hub zone 127.0.0.1:6567 aa true", player)
        clientCommand.handleMessage("/hub zone 127.0.0.1:6567 5 true", player)
        clientCommand.handleMessage("/hub block", player)
        clientCommand.handleMessage("/hub block 127.0.0.1:6567 aa", player)
        clientCommand.handleMessage("/hub count 127.0.0.1:6567", player)
        clientCommand.handleMessage("/hub total", player)

        clientCommand.handleMessage("/info", player)

        clientCommand.handleMessage("/js", player)

        clientCommand.handleMessage("/kill", player)
        clientCommand.handleMessage("/kill ${dummy.name()}", player)

        clientCommand.handleMessage("/killall derelict", player)
        clientCommand.handleMessage("/killall blue", player)
        clientCommand.handleMessage("/killall crux", player)
        clientCommand.handleMessage("/killall green", player)
        clientCommand.handleMessage("/killall malis", player)
        clientCommand.handleMessage("/killall sharded", player)

        clientCommand.handleMessage("/maps", player)
        clientCommand.handleMessage("/maps 999", player)

        clientCommand.handleMessage("/me hello!", player)

        clientCommand.handleMessage("/meme router", player)

        clientCommand.handleMessage("/motd", player)

        clientCommand.handleMessage("/mute ${dummy.name()}", player)

        clientCommand.handleMessage("/pause", player)
        clientCommand.handleMessage("/pause", player)

        clientCommand.handleMessage("/players", player)
        clientCommand.handleMessage("/players 999", player)

        clientCommand.handleMessage("/random", player)

        clientCommand.handleMessage("/search 999", player)

        clientCommand.handleMessage("/spawn unit dagger 10", player)
        clientCommand.handleMessage("/spawn unit invalid 10", player)
        clientCommand.handleMessage("/spawn block copper-wall", player)
        clientCommand.handleMessage("/spawn block invalid", player)

        clientCommand.handleMessage("/status", player)

        clientCommand.handleMessage("/team derelict", player)
        clientCommand.handleMessage("/team blue", player)
        clientCommand.handleMessage("/team crux", player)
        clientCommand.handleMessage("/team green", player)
        clientCommand.handleMessage("/team malis", player)
        clientCommand.handleMessage("/team sharded", player)

        clientCommand.handleMessage("/time", player)

        clientCommand.handleMessage("/tp ${dummy.id()}", player)

        clientCommand.handleMessage("/unmute ${dummy.name()}", player)

        clientCommand.handleMessage("/vote", player)
        clientCommand.handleMessage("/vote gg", player)

        clientCommand.handleMessage("/weather", player)

        serverCommand.handleMessage("gen")

        root.child("permission.txt").writeString(root.child("permission.txt").readString())

        Events.fire(EventType.PlayerLeave(player.self()))

        println("서비스 실행까지 기다리는 중..")
        sleep(15000)
        Core.app.listeners[1].dispose()

        // 서버 재시작 테스트
        main = Main()
        main.init()
        main.registerClientCommands(clientCommand)
        main.registerServerCommands(serverCommand)

        println("서비스 실행까지 기다리는 중.. 2차")
        sleep(2000)
        Core.app.listeners[1].dispose()
    }

    @Test
    fun midiTest(){
        var midi = false;
        var volume = 20;
        var instrument = "press"
        var pitch = 0.98

        val midiSystem = MidiSystem.getSequencer()
        val sequence = MidiSystem.getSequence(Main::class.java.classLoader.getResourceAsStream("input.mid"))
        midiSystem.open()
        midiSystem.sequence = sequence
        midiSystem.start()

        var stream = Core.files.absolute("/dev/midi3").read()
        midi = true

        val startMidiAsync = Thread {
            try {
                stream.close()
            } catch (e: Exception) {
                stream = null
            }
        }

        val parserAsync  = Thread {
            while(midi){
                try{
                    var b = stream.read()
                    if (b in 144..159 && b != 153) {
                        var p = stream.read()
                        var v = stream.read()
                        if (v != 0) {
                            Call.sendChatMessage("/js Call.sound(Sounds.$instrument,${volume*v/127},${min(pitch* 1.0594630943592953.pow(((p - 60).toDouble())),512.toDouble())},0")
                        }
                    }
                } catch (e: Exception){
                    Log.err(e)
                    midi = false
                }
            }
        }
        parserAsync.isDaemon = true

        parserAsync.start()
    }

    @Test
    fun midiTest2(){

    }

    @Test
    fun commandTest() {
        for (a in clientCommand.commandList) {
            val d = StringBuilder()
            if (a.params.isNotEmpty()) {
                for (b in a.params) {
                    d.append(b.name)
                }
                println("${a.text} ${d.toString()}")
            } else {
                println(a.text)
            }
        }

        for (a in clientCommand.commandList) {
            if (a.params.isEmpty()) {
                clientCommand.handleMessage(a.text)

            } else {

            }
            clientCommand.handleMessage(a.text)
        }
    }

    @Test
    fun client_chars() {
        clientCommand.handleMessage("/chars abcdefghijklmnopqrstuvwxyz1234567890", player)
        assert(player.tileOn().block() == Blocks.scrapWall)
    }

    @Test
    fun client_color() {
        clientCommand.handleMessage("/color", player)
        assert(player.name().contains(Regex("[[^)]*]")))
    }

    @Test
    fun client_discord() {
        clientCommand.handleMessage("/discord", player)
    }

    @Test
    fun client_effect() {
        clientCommand.handleMessage("/effect")
    }

    @Test
    fun client_fillitems() {
        clientCommand.handleMessage("/fillitems")
    }

    @Test
    fun client_freeze() {
        clientCommand.handleMessage("/freeze")
    }

    @Test
    fun client_gg() {
        clientCommand.handleMessage("/gg")
    }

    @Test
    fun client_god() {
        clientCommand.handleMessage("/god")
    }

    @Test
    fun client_help() {
        clientCommand.handleMessage("/help")
    }

    @Test
    fun client_hub() {
        clientCommand.handleMessage("/hub")
    }

    @Test
    fun client_info() {
        clientCommand.handleMessage("/info")
    }

    @Test
    fun client_js() {
        clientCommand.handleMessage("/js")
    }

    @Test
    fun client_kickall() {
        clientCommand.handleMessage("/kickall")
    }

    @Test
    fun client_kill() {
        clientCommand.handleMessage("/kill")
    }

    @Test
    fun client_killall() {
        clientCommand.handleMessage("/killall")
    }

    @Test
    fun client_language() {
        clientCommand.handleMessage("/language")
    }

    @Test
    fun client_login() {
        clientCommand.handleMessage("/login")
    }

    @Test
    fun client_maps() {
        clientCommand.handleMessage("/maps")
    }

    @Test
    fun client_me() {
        clientCommand.handleMessage("/me")
    }

    @Test
    fun client_meme() {
        clientCommand.handleMessage("/meme")
    }

    @Test
    fun client_motd() {
        clientCommand.handleMessage("/motd")
    }

    @Test
    fun client_mute() {
        clientCommand.handleMessage("/mute")
    }

    @Test
    fun client_pause() {
        clientCommand.handleMessage("/pause")
    }

    @Test
    fun client_players() {
        clientCommand.handleMessage("/players")
    }

    @Test
    fun client_reg() {
        clientCommand.handleMessage("/reg")
    }

    @Test
    fun client_report() {
        clientCommand.handleMessage("/report")
    }

    @Test
    fun client_search() {
        clientCommand.handleMessage("/search")
    }

    @Test
    fun client_setperm() {
        clientCommand.handleMessage("/setperm")
    }

    @Test
    fun client_spawn() {
        clientCommand.handleMessage("/spawn")
    }

    @Test
    fun client_status() {
        clientCommand.handleMessage("/status")
    }

    @Test
    fun client_team() {
        clientCommand.handleMessage("/team")
    }

    @Test
    fun client_tempban() {
        clientCommand.handleMessage("/tempban")
    }

    @Test
    fun client_time() {
        clientCommand.handleMessage("/time")
    }

    @Test
    fun client_tp() {
        clientCommand.handleMessage("/tp")
    }

    @Test
    fun client_unmute() {
        clientCommand.handleMessage("/unmute")
    }

    @Test
    fun client_url() {
        clientCommand.handleMessage("/url")
    }

    @Test
    fun client_weather() {
        clientCommand.handleMessage("/weather")
    }
}