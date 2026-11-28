package net.ccbluex.liquidbounce.config.types.list
interface Tagged { val tag: String; val tagAliases: List<String> get() = emptyList() }
