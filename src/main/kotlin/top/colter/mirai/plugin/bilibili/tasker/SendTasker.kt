package top.colter.mirai.plugin.bilibili.tasker

import kotlinx.coroutines.sync.withLock
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.message.code.MiraiCode
import net.mamoe.mirai.message.data.ForwardMessage
import net.mamoe.mirai.message.data.Message
import net.mamoe.mirai.message.data.RawForwardMessage
import net.mamoe.mirai.message.data.buildForwardMessage
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import top.colter.mirai.plugin.bilibili.BiliBiliDynamic
import top.colter.mirai.plugin.bilibili.BiliDynamicConfig
import top.colter.mirai.plugin.bilibili.BiliSubscribeData.dynamic
import top.colter.mirai.plugin.bilibili.data.DynamicMessage
import top.colter.mirai.plugin.bilibili.tasker.BiliDataTasker.mutex
import top.colter.mirai.plugin.bilibili.utils.CacheType
import top.colter.mirai.plugin.bilibili.utils.cachePath
import top.colter.mirai.plugin.bilibili.utils.uploadImage
import kotlin.io.path.notExists
import kotlin.io.path.readBytes

object SendTasker: BiliTasker() {

    override val interval: Int = 0

    override suspend fun main() {
        val dynamicMessage = BiliBiliDynamic.messageChannel.receive()


        //dynamicMessage.sendMessage(dynamicMessage.buildMessage())
    }

    private suspend fun getDynamicContactList(uid: Long, isVideo: Boolean): MutableSet<String>? = mutex.withLock {
        return try {
            val all = dynamic[0] ?: return null
            val list: MutableSet<String> = mutableSetOf()
            list.addAll(all.contacts.keys)
            val subData = dynamic[uid] ?: return list
            if (isVideo) list.addAll(subData.contacts.filter { it.value[0] == '1' || it.value[0] == '2' }.keys)
            else list.addAll(subData.contacts.filter { it.value[0] == '1' }.keys)
            list.removeAll(subData.banList.keys)
            list
        } catch (e: Throwable) {
            null
        }
    }

    fun DynamicMessage.sendMessage(messages: List<Message>){



    }

    private val forwardRegex = """\{>>}(.*?)\{<<}""".toRegex()

    private val tagRegex = """\{([a-z]+)}""".toRegex()

    data class ForwardDisplay(
        val title: String = "{name} {type} 详情",
        val summary: String = "ID: {did}",
        val brief: String = "[{name} {type}]",
        val preview: String = "时间: {time}\n{content}"
    )

    suspend fun DynamicMessage.buildMessage(contact: Contact): List<Message> {

        val msgList = mutableListOf<Message>()

        val msgTemplate = BiliDynamicConfig.templateConfig.dynamic.replace("\n", "\\n").replace("\r", "\\r")

        val forwardCardTemplate = ForwardDisplay()

        val res = forwardRegex.findAll(msgTemplate)

        var index = 0

        res.forEach { mr ->
            if (mr.range.first > index){
                msgList.addAll(buildMsgList(msgTemplate.substring(index, mr.range.first), this, contact))
            }
            msgList.add(buildForwardMessage(contact,
                object : ForwardMessage.DisplayStrategy {
                    override fun generateBrief(forward: RawForwardMessage): String {
                        return buildSimpleTemplate(forwardCardTemplate.brief, this@buildMessage)
                    }

                    override fun generatePreview(forward: RawForwardMessage): List<String> {
                        return buildSimpleTemplate(forwardCardTemplate.preview, this@buildMessage).split("\n")
                    }

                    override fun generateSummary(forward: RawForwardMessage): String {
                        return buildSimpleTemplate(forwardCardTemplate.summary, this@buildMessage)
                    }

                    override fun generateTitle(forward: RawForwardMessage): String {
                        return buildSimpleTemplate(forwardCardTemplate.title, this@buildMessage)
                    }
                }
                ){
                buildMsgList(mr.destructured.component1(), this@buildMessage, contact).forEach {
                    contact.bot named this@buildMessage.uname at this@buildMessage.timestamp says it
                }
            })
            index = mr.range.last + 1
        }

        if (index < msgTemplate.length){
            msgList.addAll(buildMsgList(msgTemplate.substring(index, msgTemplate.length), this, contact))
        }

        return msgList
    }

    private suspend fun buildMsgList(template: String, dm: DynamicMessage, contact: Contact): List<Message> {
        val msgs = template.split("\\r", "\r")
        val msgList = mutableListOf<Message>()
        msgs.forEach{ ms ->
            msgList.add(MiraiCode.deserializeMiraiCode(buildMsg(ms, dm, contact)))
        }
        return msgList.toList()
    }

    private fun buildSimpleTemplate(ms: String, dm: DynamicMessage): String {
        return ms.replace("{name}", dm.uname)
            .replace("{uid}", dm.uid.toString())
            .replace("{did}", dm.did)
            .replace("{time}", dm.time)
            .replace("{type}", dm.type)
            .replace("{content}", dm.content)
            .replace("{link}", dm.links?.get(0)?.value!!)
    }

    private suspend fun buildMsg(ms: String, dm: DynamicMessage, contact: Contact): String{
        var p = 0
        var content = ms

        while (true){
            val key = tagRegex.find(content, p) ?: break
            val rep = when (key.destructured.component1()){
                "name" -> dm.uname
                "uid" -> dm.uid.toString()
                "did" -> dm.did
                "time" -> dm.time
                "type" -> dm.type
                "content" -> dm.content
                "link" -> dm.links?.get(0)?.value!!
                "images" -> {
                    buildString {
                        dm.images?.forEach {
                            appendLine(uploadImage(it, CacheType.IMAGES, contact).serializeToMiraiCode())
                        }
                    }
                }
                "draw" -> {
                    if (dm.drawPath == null){
                        "[绘制动态失败]"
                    }else {
                        val path = cachePath.resolve(dm.drawPath)
                        if (path.notExists()){
                            "[未找到绘制的动态]"
                        }else {
                            contact.uploadImage(
                                cachePath.resolve(dm.drawPath).readBytes().toExternalResource().toAutoCloseable()
                            ).serializeToMiraiCode()
                        }
                    }
                }
                else -> {
                    "[不支持的类型: ${key.destructured.component1()}]"
                }
            }
            content = content.replaceRange(key.range, rep)
            p = key.range.first + rep.length
        }

        return content
    }

}