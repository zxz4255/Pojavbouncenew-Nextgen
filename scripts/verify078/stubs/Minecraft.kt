package net.ccbluex.liquidbounce.utils.client
class Gui {var current:Any?=null;fun screen()=current;fun setScreen(value:Any?){current=value}}
class Client {val gui=Gui()}
val mc=Client()
