package net.ccbluex.liquidbounce.ui.clickgui.setting

import net.ccbluex.liquidbounce.config.types.*
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.MutableListValue
import net.ccbluex.liquidbounce.ui.common.UiTextEditState
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.*
import com.mojang.blaze3d.platform.InputConstants

fun main(args:Array<String>) {
    val group=ValueGroup("AutoAccount")
    val login=group.regex("LoginRegex", Regex("/login", setOf(RegexOption.IGNORE_CASE,RegexOption.MULTILINE)))
    if (args.single()=="old") {
        try { SettingRenderer.stepGroupAnimations(group,0.016f);error("081 negative control did not reproduce") }
        catch (e:ClassCastException) {check(e.message.orEmpty().contains("Regex") && e.message.orEmpty().contains("String"));println("PASS: immutable081 reproduced Regex -> String ClassCastException via SettingRenderer.stepGroupAnimations -> TextSettingNode constructor")}
        // The other TEXT assumption corrupts regex lists before consumers cast their entries.
        val list=MutableListValue("RegexList", mutableListOf<Regex>(), ValueType.TEXT, Regex::class.java)
        val node=MutableListSettingNode(list)
        val add=node.javaClass.getDeclaredMethod("tryAddEntry",String::class.java).apply {isAccessible=true}
        add.invoke(node,"/login")
        check((list.get() as List<*>).single() is String)
        println("PASS: immutable081 negative control also reproduced String insertion into a declared Regex list")
        return
    }
    var checks=0
    fun ok(b:Boolean){check(b){"082 assertion $checks"};checks++}
    val ctx=GuiGraphicsExtractor()
    fun render()=SettingRenderer.renderGroup(ctx,group,0f,0f,320f,-1f,-1f,0.016f,1200f)
    SettingRenderer.stepGroupAnimations(group,0.016f)
    val node=SettingRenderer.nodeFor(login)
    ok(node is TextSettingNode)
    ok(SettingRenderer.nodeFor(login)===node)
    ok(render()==48f)
    val editor=node.javaClass.getDeclaredField("editor").apply {isAccessible=true}.get(node) as UiTextEditState
    ok(editor.text=="/login")
    ok(SettingRenderer.mouseClickedList(listOf(login),5f,25f,0))
    ok(editor.focused)
    var changes=0
    login.onChanged { changes++ }
    fun replace(text:String){editor.focus(true);editor.charTyped(CharacterEvent(text))}
    replace("^/REGISTER\\s+.+$")
    ok(login.get() is Regex && login.get().pattern=="^/REGISTER\\s+.+$")
    ok(login.get().options==setOf(RegexOption.IGNORE_CASE,RegexOption.MULTILINE))
    ok(changes==1)
    editor.keyPressed(KeyEvent(InputConstants.KEY_RETURN));ok(changes==1)
    val good=login.get()
    replace("[")
    ok(login.get()===good && changes==1)
    ok(editor.text=="[" && editor.focused)
    ok(node.measureHeight(320f)==62f)
    ok(render()==62f)
    ok(net.ccbluex.liquidbounce.ui.common.UiDraw.texts.any {it.contains("正则无效")})
    editor.keyPressed(KeyEvent(InputConstants.KEY_RETURN));ok(login.get()===good && changes==1)
    ok(render()==62f) // invalid draft remains editable, not discarded every frame
    editor.unfocus(true);ok(login.get()===good)
    ok(render()==48f && editor.text==good.pattern)
    replace("(")
    ok(login.get()===good)
    replace("(?:abc|中文)+")
    ok(login.get().pattern=="(?:abc|中文)+" && login.get().options==good.options)
    ok(node.measureHeight(320f)==48f)
    // Empty patterns are valid; use a real editor deletion, not a fake onChanged invocation.
    editor.focus(true);editor.keyPressed(KeyEvent(InputConstants.KEY_BACKSPACE))
    ok(login.get().pattern.isEmpty() && login.get().options==good.options)
    ok(TextSettingCodec.apply(login,""))
    val beforeCancel=login.get()
    replace("[");editor.keyPressed(KeyEvent(InputConstants.KEY_ESCAPE))
    render();ok(login.get()===beforeCancel && editor.text==beforeCancel.pattern)
    // Configuration reloads / restores are read back when unfocused, never converted to String.
    login.set(Regex("external",RegexOption.DOT_MATCHES_ALL));render()
    ok(editor.text=="external" && login.get().options==setOf(RegexOption.DOT_MATCHES_ALL))
    login.restore();render();ok(editor.text=="/login" && login.get().options==good.options)
    val plain=group.text("Password", "  initial  ")
    val stringNode=SettingRenderer.nodeFor(plain)
    ok(stringNode is TextSettingNode)
    val plainEditor=stringNode.javaClass.getDeclaredField("editor").apply {isAccessible=true}.get(stringNode) as UiTextEditState
    plainEditor.focus(true);plainEditor.charTyped(CharacterEvent(" [not-regex] 中文 "))
    ok(plain.get()==" [not-regex] 中文 " && plain.get() is String)
    plainEditor.keyPressed(KeyEvent(InputConstants.KEY_RETURN));plainEditor.unfocus(true)
    ok(plain.get()==" [not-regex] 中文 ")
    val unknown=Value("OddText",defaultValue=17,valueType=ValueType.TEXT)
    group.inner.add(unknown)
    SettingRenderer.stepGroupAnimations(group,0.016f);render()
    ok(SettingRenderer.nodeFor(unknown) !is TextSettingNode)
    ok(!TextSettingCodec.apply(unknown,"21") && unknown.get()==17)
    ok(!TextSettingCodec.supports(unknown))
    // Regex lists: use the actual declared innerType, even with zero existing entries.
    val regexes=MutableListValue("Filters",mutableListOf<Regex>(),ValueType.TEXT,Regex::class.java)
    val listNode=SettingRenderer.nodeFor(regexes)
    ok(listNode is MutableListSettingNode<*,*>)
    val add=listNode.javaClass.getDeclaredMethod("tryAddEntry",String::class.java).apply {isAccessible=true}
    fun add(text:String){add.invoke(listNode,text)}
    add("/login")
    ok((regexes.get() as List<*>).single() is Regex)
    ok(regexes.get().single().matches("/login"))
    add("[");ok(regexes.get().size==1)
    add(" ");ok(regexes.get().size==1)
    add(" ^x$ ");ok(regexes.get().last().pattern==" ^x$ ")
    val strings=MutableListValue("Words",mutableListOf<String>(),ValueType.TEXT,String::class.java)
    val stringsNode=MutableListSettingNode(strings)
    val addString=stringsNode.javaClass.getDeclaredMethod("tryAddEntry",String::class.java).apply {isAccessible=true}
    addString.invoke(stringsNode,"  [literal]  ")
    ok(strings.get().single()=="[literal]")
    // Factory-generated regexList/textList maintain the same TEXT schema and declared class.
    val factoryRegexes=group.regexList("FactoryRegex",mutableListOf<Regex>())
    ok(factoryRegexes.innerType==Regex::class.java && factoryRegexes.innerValueType==ValueType.TEXT)
    val factoryStrings=group.textList("FactoryString",mutableListOf<String>())
    ok(factoryStrings.innerType==String::class.java && factoryStrings.innerValueType==ValueType.TEXT)
    ok(login.valueType==ValueType.TEXT)
    // Stress the full actual dispatch/visibility/cache/render path across opening and editing.
    repeat(2000){i ->
        SettingRenderer.prepareForOpen()
        SettingRenderer.stepGroupAnimations(group,0.016f)
        render()
        ok(SettingRenderer.nodeFor(login)===node)
        ok(TextSettingCodec.apply(login,"^value${i}$"))
        ok(!TextSettingCodec.apply(login,"["))
        ok(login.get().matches("value$i"))
        ok(login.get().options==good.options)
    }
    ok(net.ccbluex.liquidbounce.utils.client.logger.errors==0)
    println("PASS: 082 $checks assertions: actual Value/listener, dispatcher/cache/animation/render, actual text editor input, Regex/String type preservation, invalid draft/commit/blur/Escape, Regex options, restore/external updates, unsupported TEXT fallback, empty regex-list safe addition and 2000 reopen/edit cycles; game/drawing hosts are fixtures")
}
