package com.coda.mobileui.ext

import java.io.File

/**
 * 分类契约。
 *
 * 每个分类提供两副面孔：
 *
 * 1. 对外能力（[capability]）——分类把已启用的拓展合成为一份标准能力，交给宿主与内核消费；
 * 2. 拓展须实现的接口（[load]）——想接入本分类的拓展必须满足的能力，由分类自己定义与校验。
 *
 * 引擎只负责发现、装载、启停与生命周期，至于"这个分类的能力长什么样、给谁用"完全归分类。
 * 新增分类只需实现本接口并注册到引擎，引擎与已有分类都不用改。
 */
interface ExtensionCategory {

    /** 分类标识，与清单里的 `category` 字段一致。 */
    val id: String

    /** 展示名。 */
    val title: String

    /** 清单里必须存在的分类专属键（形如 `terminal.shell`）；缺失即视为不符合本分类契约。 */
    val requiredManifestKeys: List<String>

    /**
     * 拓展须实现的接口：把清单声明装载成该分类的 provider。
     * 返回 null 表示清单不满足契约（缺少必需键）。
     */
    fun load(context: ExtensionLoadContext): ExtensionProvider?

    /**
     * 对外能力：把已启用拓展的 provider 合成为标准能力。
     * 没有可用拓展时返回 null，避免上游拿到一个空能力对象。
     */
    fun capability(providers: List<ExtensionProvider>): ExtensionCapability?
}

/**
 * 装载上下文。
 *
 * [packageDir] 是随包分发的只读目录，[runtimeDir] 是可写目录，二进制与运行期状态释放到这里，
 * 这样拓展包本身可以保持不可变，重装或回滚只影响运行目录。
 */
data class ExtensionLoadContext(
    val packageDir: File,
    val runtimeDir: File,
    val manifest: ExtensionManifest,
)

/** 已装载拓展在某分类下的运行时接口。 */
interface ExtensionProvider {

    val extensionId: String

    /** 清单里声明的二进制是否已经释放到运行目录且可用。 */
    val available: Boolean

    /** 装载阶段准备（释放二进制、建立运行目录）。失败时拓展标记为不可用。 */
    fun prepare(): Boolean

    /** 停用或卸载前的清理。 */
    fun release() {}
}

/** 分类对外能力的公共标记。 */
interface ExtensionCapability {
    val categoryId: String
}
