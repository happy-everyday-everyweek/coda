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

    /** assets 下的五个内置形象，顺序与原项目设置页一致。 */
    private val BUILTIN = listOf("clawd", "sprig", "mochi", "peep", "bristle")

    /** 原项目没给 clawd 写 title，设置里就叫它 Clawd。 */
    private val TITLE_OVERRIDE = mapOf("clawd" to "Clawd")

    @Volatile
    private var cache: List<PetSkin>? = null

    /** 默认形象：原项目的默认是 Clawd。 */
    val defaultId: String = "clawd"

    /**
     * 全部可用皮肤：五个内置 + filesDir/pet 下的自定义。
     *
     * 自定义 manifest 解析失败的会被跳过（不会让设置页炸掉）；id 与内置重名时优先用自定义那份。
     */
    fun all(ctx: Context): List<PetSkin> {
        cache?.let { if (it.isNotEmpty()) return mergeCustom(ctx, it) }
        val loaded = ArrayList<PetSkin>(BUILTIN.size)
        for (name in BUILTIN) {
            val skin = runCatching { loadAsset(ctx, name) }.getOrNull() ?: continue
            loaded.add(skin)
        }
        cache = loaded
        return mergeCustom(ctx, loaded)
    }

    private fun mergeCustom(ctx: Context, base: List<PetSkin>): List<PetSkin> {
        val dir = File(ctx.filesDir, "pet")
        if (!dir.isDirectory) return base
        val custom = ArrayList<PetSkin>()
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json", true) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                runCatching { PetSkin.fromJson(file.readText()) }
                    .onSuccess { parsed -> custom.add(parsed) }
            }
        if (custom.isEmpty()) return base
        val out = ArrayList<PetSkin>(base.size + custom.size)
        for (skin in base) {
            out.add(custom.firstOrNull { it.id == skin.id } ?: skin)
        }
        custom.filter { c -> base.none { it.id == c.id } }.forEach { out.add(it) }
        return out
    }

    /** 按 id 取皮肤；找不到退回默认（Clawd），再找不到退回第一份。 */
    fun resolve(ctx: Context, id: String): PetSkin {
        val list = all(ctx)
        return list.firstOrNull { it.id == id }
            ?: list.firstOrNull { it.id == defaultId }
            ?: list.firstOrNull()
            ?: loadAsset(ctx, defaultId)
    }

    /** 读一份内置 manifest。读不到是真错误（assets 应该随包提供），抛出来便于定位。 */
    private fun loadAsset(ctx: Context, name: String): PetSkin {
        val raw = ctx.assets.open("pet/$name.json").bufferedReader().use { it.readText() }
        val skin = PetSkin.fromJson(raw)
        val title = TITLE_OVERRIDE[name]
        return if (title != null && skin.title == skin.id) {
            // 原项目 clawd 只有 name 没有 title，标题会退回 name；这里补成 Clawd
            PetSkin(
                schema = skin.schema, id = skin.id, title = title, tickMs = skin.tickMs,
                cols = skin.cols, rows = skin.rows, palette = skin.palette, parts = skin.parts,
                poses = skin.poses, glyphs = skin.glyphs, clips = skin.clips,
                effects = skin.effects, accessories = skin.accessories, states = skin.states,
                interactions = skin.interactions, props = skin.props,
            )
        } else {
            skin
        }
    }

    /** 全部形象名，给设置页列清单用。 */
    fun ids(): List<String> = BUILTIN
}