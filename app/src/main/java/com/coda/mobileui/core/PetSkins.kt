package com.coda.mobileui.core

import android.content.Context
import java.io.File

/**
 * 皮肤库。
 *
 * 内置的五份形象是 critter（https://github.com/Brayn-Studio/critter，MIT）原样搬来的 manifest，
 * 放在 assets/pet/ 下，文件名即形象名。Clawd 是它的默认形象（Claude Code 的吉祥物，
 * 形象版权归 Anthropic）；小芽 Sprig、团子 Mochi、啾啾 Peep、刺球 Bristle 是该项目原创。
 *
 * 这里不再自造任何像素：要改形象就改 manifest，代码不认识具体部件名。
 */
object PetSkins {
    private const val TAG = "PetSkins"

    /** assets 下的七个内置形象，顺序与原项目设置页一致。 */
    private val BUILTIN = listOf("clawd", "sprig", "mochi", "peep", "bristle", "deepy", "whalegirl")

    /** 原项目没给 clawd 写 title，设置里就叫它 Clawd。 */
    private val TITLE_OVERRIDE = mapOf("clawd" to "Clawd")

    @Volatile
    private var cache: List<PetSkin>? = null

    /** 默认形象：原项目的默认是 Clawd。 */
    val defaultId: String = "clawd"

    /**
     * 全部可用皮肤：七个内置 + filesDir/pet 下的自定义。
     *
     * 自定义 manifest 解析失败的会被跳过（不会让设置页炸掉）；id 与内置重名时优先用自定义那份。
     */
    fun all(ctx: Context): List<PetSkin> {
        cache?.let { if (it.isNotEmpty()) return mergeCustom(ctx, it) }
        val loaded = ArrayList<PetSkin>(BUILTIN.size)
        for (name in BUILTIN) {
            // 不能静默跳过：资产缺失时后面 resolve 会回落到默认皮肤并抛异常，
            // 没有这里的日志就查不到是哪一个 manifest 坏的。
            val skin =
                try {
                    loadAsset(ctx, name)
                } catch (e: Exception) {
                    AppLog.w(TAG, "内置皮肤 $name 加载失败：${e.message ?: e.javaClass.simpleName}")
                    continue
                }
            loaded.add(skin)
        }
        cache = loaded
        return mergeCustom(ctx, loaded)
    }

    /**
     * 自定义目录的指纹（文件名 + 长度 + 修改时间）。
     * 内容没变就不必重新读盘解析——all()/resolve() 会被 UI 反复调用（#338）。
     */
    // 签名与列表必须原子发布（OCR #820）：分开的两个 var 会让并发 all()
    // 看到新签名配旧列表，之后一直走快路径，自定义皮肤永远加载不出来。
    private class CustomEntry(
        val signature: String,
        val skins: List<PetSkin>,
    )

    @Volatile
    private var customEntry: CustomEntry? = null


    private fun signatureOf(dir: File): String {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json", true) } ?: return ""
        return files.sortedBy { it.name }.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
    }

    private fun mergeCustom(
        ctx: Context,
        base: List<PetSkin>,
    ): List<PetSkin> {
        val dir = File(ctx.filesDir, "pet")
        if (!dir.isDirectory) return base
        val sig = signatureOf(dir)
        customEntry?.let { if (it.signature == sig) return applyCustom(base, it.skins) }
        val custom = ArrayList<PetSkin>()
        dir
            .listFiles { f -> f.isFile && f.name.endsWith(".json", true) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                runCatching { PetSkin.fromJson(file.readText()) }
                    .onSuccess { parsed ->
                        // 同 id 去重（文件名排序后先到先得）：重复 id 会让皮肤列表
                        // 出现重复条目，选择状态也会不一致。
                        if (custom.none { it.id == parsed.id }) {
                            custom.add(parsed)
                        } else {
                            AppLog.w(TAG, "自定义皮肤 id 重复，跳过 ${file.name}：${parsed.id}")
                        }
                    }
                    // 解析失败不能静默（OCR low #829）：加载期校验让一批以前能解析的
                    // 自定义皮肤开始抛异常，用户只会看到它从列表里消失，却查不到原因。
                    .onFailure { AppLog.w(TAG, "自定义皮肤 ${file.name} 解析失败：${it.message}") }
            }
        // 解析完成后一次性发布签名+列表。
        customEntry = CustomEntry(sig, custom)
        return applyCustom(base, custom)
    }

    /** 自定义覆盖同 id 的内置项，其余追加在后。 */
    private fun applyCustom(
        base: List<PetSkin>,
        custom: List<PetSkin>,
    ): List<PetSkin> {
        if (custom.isEmpty()) return base
        val out = ArrayList<PetSkin>(base.size + custom.size)
        for (skin in base) {
            out.add(custom.firstOrNull { it.id == skin.id } ?: skin)
        }
        custom.filter { c -> base.none { it.id == c.id } }.forEach { out.add(it) }
        return out
    }

    /** 按 id 取皮肤；找不到退回默认（Clawd），再找不到退回第一份。 */
    fun resolve(
        ctx: Context,
        id: String,
    ): PetSkin {
        val list = all(ctx)
        return list.firstOrNull { it.id == id }
            ?: list.firstOrNull { it.id == defaultId }
            ?: list.firstOrNull()
            ?: runCatching { loadAsset(ctx, defaultId) }.getOrElse { e ->
                // 内置资产随包提供，走到这里说明打包已经坏了。带上皮肤名抛出去，
                // 比让上层看到一个没有上下文的 IO 异常更容易定位。
                AppLog.e(TAG, "默认皮肤 $defaultId 加载失败", e)
                throw IllegalStateException("内置皮肤资源缺失：$defaultId", e)
            }
    }

    /** 读一份内置 manifest。读不到是真错误（assets 应该随包提供），抛出来便于定位。 */
    private fun loadAsset(
        ctx: Context,
        name: String,
    ): PetSkin {
        val raw =
            ctx.assets
                .open("pet/$name.json")
                .bufferedReader()
                .use { it.readText() }
        val skin = PetSkin.fromJson(raw)
        val title = TITLE_OVERRIDE[name]
        return if (title != null && skin.title == skin.id) {
            // 原项目 clawd 只有 name 没有 title，标题会退回 name；这里补成 Clawd。
            // data class 的 copy 只改 title：逐字段重建会在新增字段时静默丢数据。
            skin.copy(title = title)
        } else {
            skin
        }
    }

}
