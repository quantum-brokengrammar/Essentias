package essentials

import arc.Core
import arc.func.Boolf
import essentials.Main.Companion.colorNickname
import essentials.Main.Companion.playerCore
import essentials.Main.Companion.pluginData
import essentials.Main.Companion.pluginRoot
import essentials.Main.Companion.pluginVars
import essentials.Main.Companion.tool
import essentials.internal.CrashReport
import essentials.internal.Log
import essentials.internal.PluginException
import mindustry.Vars
import mindustry.entities.type.Player
import mindustry.gen.Call
import mindustry.net.Packets
import org.h2.Driver
import org.h2.tools.Server
import org.hjson.JsonObject
import org.hjson.JsonType
import org.mindrot.jbcrypt.BCrypt
import java.net.InetAddress
import java.net.NetworkInterface
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.function.Consumer
import kotlin.system.exitProcess

class PlayerCore {
    lateinit var conn: Connection
    var server: Server? = null

    operator fun get(uuid: String): PlayerData {
        for (p in pluginVars.playerData) {
            if (p.uuid == uuid) return p
        }
        return PlayerData()
    }

    fun playerLoad(p: Player, id: String?): Boolean {
        remove(p.uuid)

        val playerData: PlayerData? = if (id == null) {
            load(p.uuid, null)
        } else {
            load(p.uuid, id)
        }

        if (playerData == null || playerData.error) {
            CrashReport(Exception("DATA NOT FOUND"))
            return false
        }

        if (LocalDateTime.now().isBefore(tool.longToDateTime(playerData.bantime))) {
            Vars.netServer.admins.banPlayerID(p.uuid)
            Call.onKick(p.con, Packets.KickReason.banned)
            return false
        }

        val motd = tool.getMotd(playerData.locale)
        val count = motd.split("\r\n|\r|\n").toTypedArray().size
        if (count > 10) {
            Call.onInfoMessage(p.con, motd)
        } else if (motd.isNotEmpty()) {
            p.sendMessage(motd)
        }

        if (playerData.colornick) colorNickname.targets.add(p)

        val oldUUID = playerData.uuid

        playerData.uuid = p.uuid
        playerData.connected = true
        playerData.lastdate = System.currentTimeMillis()
        playerData.connserver = pluginVars.serverIP
        playerData.joincount = playerData.joincount + 1
        playerData.exp = playerData.exp + playerData.joincount
        playerData.login = true

        Main.perm.setUserPerm(oldUUID, p.uuid)
        if (Main.perm.user[p.uuid] == null) {
            Main.perm.create(playerData)
            Main.perm.saveAll()
        } else {
            p.name = Main.perm.user[playerData.uuid].asObject()["name"].asString()
        }
        p.isAdmin = Main.perm.isAdmin(playerData)
        return true
    }

    fun newData(name: String, uuid: String, country: String, country_code: String, language: String, connected: Boolean, connserver: String, permission: String, udid: Long, accountid: String, accountpw: String, isLogin: Boolean): PlayerData {
        return PlayerData(
                name,
                uuid,
                country,
                country_code,
                language,
                false,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                "none",
                "none",
                "none",
                0L,
                0,
                0,
                0,
                0,
                0,
                0L,
                translate = false,
                crosschat = false,
                colornick = false,
                connected = false,
                connserver = connserver,
                permission = permission,
                mute = false,
                alert = false,
                udid = udid,
                accountid = accountid,
                accountpw = accountpw,
                login = isLogin
        )
    }

    fun isLocal(player: Player): Boolean {
        return try {
            val addr = InetAddress.getByName(player.con.address)
            if (addr.isAnyLocalAddress || addr.isLoopbackAddress) true else NetworkInterface.getByInetAddress(addr) != null
        } catch (e: Exception) {
            false
        }
    }

    fun login(id: String, pw: String): Boolean {
        try {
            playerCore.conn.prepareStatement("SELECT * from players WHERE accountid=?").use { pstmt ->
                pstmt.setString(1, id)
                pstmt.executeQuery().use { rs ->
                    return if (rs.next()) {
                        BCrypt.checkpw(pw, rs.getString("accountpw"))
                    } else {
                        false
                    }
                }
            }
        } catch (e: RuntimeException) {
            return false
        } catch (e: SQLException) {
            CrashReport(e)
            return false
        }
    }

    fun ban(player: Player, hours: Long, reason: String) {
        val playerData = playerCore[player.uuid]
        val banTime = System.currentTimeMillis() + 1000 * 60 * 60 * hours

        playerData.bantime = banTime
        pluginData.banned.add(PluginData.Banned(banTime, playerData.name, playerData.uuid, reason))
    }

    fun remove(uuid: String) {
        pluginVars.removePlayerData(Boolf { p: PlayerData -> p.uuid == uuid })
    }

    fun load(uuid: String, id: String?): PlayerData {
        val sql = StringBuilder()
        sql.append("SELECT * FROM players WHERE uuid=?")
        if (id != null) sql.append(" OR accountid=?")
        try {
            conn.prepareStatement(sql.toString()).use { pstmt ->
                pstmt.setString(1, uuid)
                if (id != null) pstmt.setString(2, id)
                pstmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val data = PlayerData(
                                rs.getString("name"),
                                rs.getString("uuid"),
                                rs.getString("country"),
                                rs.getString("country_code"),
                                rs.getString("language"),
                                rs.getBoolean("isAdmin"),
                                rs.getInt("placecount"),
                                rs.getInt("breakcount"),
                                rs.getInt("killcount"),
                                rs.getInt("deathcount"),
                                rs.getInt("joincount"),
                                rs.getInt("kickcount"),
                                rs.getInt("level"),
                                rs.getInt("exp"),
                                rs.getInt("reqexp"),
                                rs.getLong("firstdate"),
                                rs.getLong("lastdate"),
                                rs.getString("lastplacename"),
                                rs.getString("lastbreakname"),
                                rs.getString("lastchat"),
                                rs.getLong("playtime"),
                                rs.getInt("attackclear"),
                                rs.getInt("pvpwincount"),
                                rs.getInt("pvplosecount"),
                                rs.getInt("pvpbreakout"),
                                rs.getInt("reactorcount"),
                                rs.getLong("bantime"),
                                rs.getBoolean("translate"),
                                rs.getBoolean("crosschat"),
                                rs.getBoolean("colornick"),
                                rs.getBoolean("connected"),
                                rs.getString("connserver"),
                                rs.getString("permission"),
                                rs.getBoolean("mute"),
                                rs.getBoolean("alert"),
                                rs.getLong("udid"),
                                rs.getString("accountid"),
                                rs.getString("accountpw"),
                                false
                        )
                        pluginVars.playerData.add(data)
                        return data
                    }
                }
            }
        } catch (e: SQLException) {
            CrashReport(e)
        }
        return PlayerData()
    }

    fun save(playerData: PlayerData): Boolean {
        val sql = StringBuilder()
        if (playerData.error) return false
        val js = playerData.toMap()
        sql.append("UPDATE players SET ")
        js.forEach(Consumer { s: JsonObject.Member ->
            val buf = s.name.toLowerCase() + "=?, "
            sql.append(buf)
        })
        sql.deleteCharAt(sql.length - 2)
        sql.append(" WHERE uuid=?")
        try {
            playerCore.conn.prepareStatement(sql.toString()).use { p ->
                js.forEach(object : Consumer<JsonObject.Member> {
                    var index = 1
                    override fun accept(o: JsonObject.Member) {
                        try {
                            val data = o.value
                            val value = o.value.asRaw<Any>()
                            if (value is String) {
                                p.setString(index, data.asString())
                            } else if (value is Boolean) {
                                p.setBoolean(index, data.asBoolean())
                            } else if (value is Int) {
                                p.setInt(index, data.asInt())
                            } else if (value is Long) {
                                p.setLong(index, data.asLong())
                            } else {
                                if (o.value.type == JsonType.NUMBER) {
                                    p.setInt(index, data.asInt())
                                } else {
                                    Log.err(index.toString() + "/" + o.name + "/" + o.value.toString() + "/" + o.value.type.name)
                                    Core.app.dispose()
                                    Core.app.exit()
                                }
                            }
                        } catch (e: SQLException) {
                            CrashReport(e)
                        }
                        index++
                    }
                })
                p.setString(js.size() + 1, playerData.uuid)
                return p.execute()
            }
        } catch (e: SQLException) {
            CrashReport(e)
            return false
        }
    }

    fun saveAll() {
        for (p in pluginVars.playerData) {
            save(p)
        }
    }

    fun register(name: String, uuid: String, country: String, country_code: String, language: String, connected: Boolean, connserver: String, permission: String, udid: Long, accountid: String, accountpw: String, isLogin: Boolean): Boolean {
        val sql = StringBuilder()
        sql.append("INSERT INTO players VALUES(")
        val newdata = playerCore.newData(name, uuid, country, country_code, language, connected, connserver, permission, udid, accountid, accountpw, isLogin)
        val js = newdata.toMap()
        js.forEach(Consumer { sql.append("?,") })
        sql.deleteCharAt(sql.length - 1)
        sql.append(")")
        try {
            playerCore.conn.prepareStatement(sql.toString()).use { p ->
                js.forEach(object : Consumer<JsonObject.Member> {
                    var index = 1
                    override fun accept(o: JsonObject.Member) {
                        try {
                            val data = o.value
                            val value = o.value.asRaw<Any>()
                            if (value is String) {
                                p.setString(index, data.asString())
                            } else if (value is Boolean) {
                                p.setBoolean(index, data.asBoolean())
                            } else if (value is Int) {
                                p.setInt(index, data.asInt())
                            } else if (value is Long) {
                                p.setLong(index, data.asLong())
                            } else {
                                if (o.value.type == JsonType.NUMBER) {
                                    p.setInt(index, data.asInt())
                                } else {
                                    Log.err(index.toString() + "/" + o.name + "/" + o.value.toString() + "/" + o.value.type.name)
                                    Core.app.dispose()
                                    Core.app.exit()
                                }
                            }
                        } catch (e: SQLException) {
                            CrashReport(e)
                        }
                        index++
                    }
                })
                val count = p.executeUpdate()
                return count > 0
            }
        } catch (e: SQLException) {
            CrashReport(e)
            return false
        }
    }

    fun create() {
        val data = "CREATE TABLE IF NOT EXISTS players (" +
                "name TEXT NOT NULL," +
                "uuid TEXT NOT NULL," +
                "country TEXT NOT NULL," +
                "country_code TEXT NOT NULL," +
                "language TEXT NOT NULL," +
                "isadmin TINYINT(4) NOT NULL," +
                "placecount INT(11) NOT NULL," +
                "breakcount INT(11) NOT NULL," +
                "killcount INT(11) NOT NULL," +
                "deathcount INT(11) NOT NULL," +
                "joincount INT(11) NOT NULL," +
                "kickcount INT(11) NOT NULL," +
                "level INT(11) NOT NULL," +
                "exp INT(11) NOT NULL," +
                "reqexp INT(11) NOT NULL," +
                "firstdate TEXT NOT NULL," +
                "lastdate TEXT NOT NULL," +
                "lastplacename TEXT NOT NULL," +
                "lastbreakname TEXT NOT NULL," +
                "lastchat TEXT NOT NULL," +
                "playtime TEXT NOT NULL," +
                "attackclear INT(11) NOT NULL," +
                "pvpwincount INT(11) NOT NULL," +
                "pvplosecount INT(11) NOT NULL," +
                "pvpbreakout INT(11) NOT NULL," +
                "reactorcount INT(11) NOT NULL," +
                "bantime TINYTEXT NOT NULL," +
                "translate TINYINT(4) NOT NULL," +
                "crosschat TINYINT(4) NOT NULL," +
                "colornick TINYINT(4) NOT NULL," +
                "connected TINYINT(4) NOT NULL," +
                "connserver TINYTEXT NOT NULL DEFAULT 'none'," +
                "permission TINYTEXT NOT NULL DEFAULT 'default'," +
                "mute TINYTEXT NOT NULL," +
                "alert TINYTEXT NOT NULL," +
                "udid TEXT NOT NULL," +
                "accountid TEXT NOT NULL," +
                "accountpw TEXT NOT NULL" +
                ")"
        try {
            conn.prepareStatement(data).use { pstmt -> pstmt.execute() }
        } catch (e: SQLException) {
            CrashReport(e)
        }
    }

    @Throws(SQLException::class)
    fun connect(isServer: Boolean) {
        Driver.load()
        if (isServer) {
            server = Server.createTcpServer("-tcpPort", "9079", "-tcpAllowOthers", "-tcpDaemon", "-baseDir", "./" + pluginRoot.child("data").path(), "-ifNotExists").start()
            conn = DriverManager.getConnection("jdbc:h2:tcp://localhost:9079/player", "", "")
        } else {
            conn = DriverManager.getConnection(Main.configs.dbUrl)
        }
    }

    @Throws(SQLException::class)
    fun disconnect() {
        conn.close()
    }

    fun dispose() {
        try {
            disconnect()
            if (server != null) server!!.stop()
        } catch (e: Exception) {
            CrashReport(e)
        }
    }

    fun update() {
        // playtime HH:mm:ss -> long 0
        try {
            conn.prepareStatement("SELECT uuid, playtime FROM players").use { pstmt ->
                pstmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        if (rs.getString("playtime").contains(":")) {
                            conn.prepareStatement("UPDATE players SET playtime=? WHERE uuid=?").use { pstmt2 ->
                                pstmt2.setLong(1, 0)
                                pstmt2.setString(2, rs.getString("uuid"))
                                val result = pstmt2.executeUpdate()
                                if (result == 0) {
                                    CrashReport(PluginException("Database update error"))
                                    exitProcess(1)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            CrashReport(e)
        }
    }
}