package net.ccbluex.liquidbounce.config
import java.io.File
object ConfigSystem { val rootFolder=File(System.getProperty("verify.layoutDir", ".")); fun store(target: Any) {} }
