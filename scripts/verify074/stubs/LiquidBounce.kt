package net.ccbluex.liquidbounce
object LiquidBounce {
 const val CLIENT_NAME = "verify074"
 fun identifier(s: String) = net.minecraft.resources.Identifier.fromNamespaceAndPath("liquidbounce", s)
 fun resourceToString(path: String) = java.io.File("src/main/resources/resources/liquidbounce", path).readText()
}
