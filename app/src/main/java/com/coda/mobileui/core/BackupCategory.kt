package com.coda.mobileui.core

import org.json.JSONArray

/**
 * 备份分类（#732）。
 *
 * 之前 export 没有任何选择参数，固定打包全部内容：整个 shared_prefs、供应商三件套、
 * 整个 filesDir（含数百 MB 的 Linux rootfs）、databases、no_backup、两份提示词。
 * 结果是只想备份设置也要等几分钟，想把配置分享给别人又无法剔除 API Key 与 OAuth 凭据。
 *
 * 这里把「条目属于哪一类」抽成纯判定，导出与导入两侧共用同一份规则：
 * 导出按勾选过滤，导入按包内实际存在的分类恢复，未包含的分类保持原样不动。
 *
 * @param key manifest 里的稳定标识，改名会让旧包读不出分类。
 * @param sensitive 含凭据（API Key / OAuth token），分享前应提示用户剔除。
 * @param bulky 体积大、耗时长，勾选前给出预期。
 */
enum class BackupCategory(
    val key: String,
    val title: String,
    val sensitive: Boolean = false,
    val bulky: Boolean = false,
) {
    /** 界面与核心设置：全部 SharedPreferences，不只内置两份。 */
    PREFS("prefs", "界面与核心设置", sensitive = true),

    /** 模型供应商：config + meta + ASR 三件套绑为一组，不拆散。 */
    PROVIDERS("providers", "模型供应商", sensitive = true),

    /** 用户级与工作区级提示词。 */
    PROMPTS("prompts", "提示词"),

    /** filesDir 下除 Linux 根文件系统外的用户文件。 */
    USER_FILES("user_files", "用户文件", bulky = true),

    /** Linux 根文件系统：终端装过的包、网关凭据、代理配置都在里面。 */
    ROOTFS("rootfs", "Linux 环境", sensitive = true, bulky = true),

    /** 应用数据库。 */
    DATABASES("databases", "数据库"),

    /** no_backup 私有持久区，部分凭据放在这里。 */
    NO_BACKUP("no_backup", "凭据与缓存区", sensitive = true),
    ;

    /** 这个分类在包内对应的固定条目名（目录型分类为空）。 */
    val entriesInPackage: Set<String>
        get() =
            when (this) {
                PROVIDERS -> PROVIDER_ENTRIES
                PROMPTS -> PROMPT_ENTRIES
                else -> EMPTY_ENTRIES
            }

    companion object {
        // OCR #819：of() 每个 zip 条目/文件都要查一次，恢复与统计可达几十万次；
        // 计算属性每次访问都新建 setOf 纯属分配浪费。常量集合提升到 companion。
        private val PROVIDER_ENTRIES = setOf("provider_config.json", "coda-provider-meta.json", "coda-asr-providers.json")
        private val PROMPT_ENTRIES = setOf("prompts/global-AGENTS.md", "prompts/workspace-AGENTS.md")
        private val EMPTY_ENTRIES = emptySet<String>()
        /** 默认全选：保持现有全量备份行为不变。 */
        val DEFAULT: Set<BackupCategory> = entries.toSet()

        /** Linux rootfs 在包内的路径前缀。 */
        private const val ROOTFS_PREFIX = "files/linux/"

        /** 必须始终写入的元数据条目，不受勾选影响。 */
        private val ALWAYS = setOf("manifest.json", "symlinks.txt", "file-modes.txt")

        /**
         * 条目属于哪个分类；元数据与未知条目返回 null。
         *
         * rootfs 要先判：它也落在 `files/` 下，先判 USER_FILES 会把整个 Linux 环境
         * 混进「用户文件」，勾选时无法单独剔除。
         */
        fun of(entryName: String): BackupCategory? =
            when {
                entryName.isEmpty() || entryName in ALWAYS -> null
                entryName.startsWith("prefs/") -> PREFS
                entryName in PROVIDERS.entriesInPackage -> PROVIDERS
                entryName.startsWith("prompts/") -> PROMPTS
                entryName.startsWith(ROOTFS_PREFIX) -> ROOTFS
                entryName.startsWith("files/") -> USER_FILES
                entryName.startsWith("databases/") -> DATABASES
                entryName.startsWith("no-backup/") -> NO_BACKUP
                else -> null
            }

        /** 导出侧：这个条目该不该写进包。元数据无条件写。 */
        fun shouldExport(
            entryName: String,
            selected: Set<BackupCategory>,
        ): Boolean {
            if (entryName in ALWAYS) return true
            val cat = of(entryName) ?: return false
            return cat in selected
        }

        /**
         * 导入侧：这个条目该不该恢复。
         *
         * [inPackage] 是包内**实际存在**的分类，不是用户勾选。部分包里不存在的分类
         * 一律不动——绝不能因为「读不到内容」就写一份空数据覆盖本机配置。
         */
        fun shouldRestore(
            entryName: String,
            inPackage: Set<BackupCategory>,
        ): Boolean {
            if (entryName in ALWAYS) return true
            val cat = of(entryName) ?: return false
            return cat in inPackage
        }

        /**
         * 过滤清单条目：只保留指向已导出路径的那些。
         *
         * 部分包若把未导出路径留在 symlinks.txt / file-modes.txt 里，恢复时会出现
         * 悬空链接或对不存在的文件 chmod。目录级条目按前缀匹配——清单里记的可能是
         * `files/linux/rootfs/bin`，而导出集合里是其下的具体文件。
         */
        fun filterManifest(
            entries: List<String>,
            exported: Set<String>,
        ): List<String> =
            entries.filter { e ->
                e in exported || exported.any { it.startsWith("$e/") }
            }

        /** 写进 manifest 的分类清单。 */
        fun toJson(selected: Set<BackupCategory>): JSONArray {
            val arr = JSONArray()
            // 按枚举声明序输出，保证同一组勾选产生同一份 JSON，便于比对。
            entries.filter { it in selected }.forEach { arr.put(it.key) }
            return arr
        }

        /**
         * 读 manifest 的分类清单。
         *
         * null 或空数组按全选处理：schema 1~2 的旧全量包没有这个字段，
         * 必须仍能完整恢复。未知 key 忽略（向前兼容），但**全部未知时退回全选**——
         * 空集会导致「什么都不恢复」，而用户会以为导入成功了，比恢复到全选更危险。
         */
        fun parseManifest(arr: JSONArray?): Set<BackupCategory> {
            if (arr == null || arr.length() == 0) return DEFAULT
            val out = mutableSetOf<BackupCategory>()
            for (i in 0 until arr.length()) {
                val key = arr.optString(i)
                entries.firstOrNull { it.key == key }?.let { out.add(it) }
            }
            return if (out.isEmpty()) DEFAULT else out
        }

        /** 是不是部分包（用于 manifest 的 partial 标记与导入确认界面）。 */
        fun isPartial(selected: Set<BackupCategory>): Boolean = selected.size != entries.size
    }
}