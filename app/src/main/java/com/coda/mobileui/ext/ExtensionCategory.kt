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
     *
     * 必需键的存在性由**引擎**统一校验（见 ExtensionEngine.loadEntry 对
     * [requiredManifestKeys] 的预检），分类实现不必也不应重复判定——两处各判一次
     * 会给出不同的错误文案与判定口径（#475）。
     *
     * 返回 null 表示「键都在，但值无法构成可用 provider」，例如 shell 规格非法、
     * 二进制路径解析不出来这类语义问题。
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

    /**
     * 装载阶段的最后一步：校验并解析运行期需要的路径，成功后置 [available]。
     *
     * 注意分工（#477）：二进制释放与运行目录创建由**引擎**在此之前完成
     * （releaseBinaries / runtimeDir.mkdirs），本方法不做这两件事。
     *
     * 返回 [PrepareResult] 而不是 Boolean（#476）：失败时带上可展示的原因，
     * 调用方才能区分路径不存在、权限不足、格式错误，而不是笼统的「装载失败」。
     */
    fun prepare(): PrepareResult

    /** 停用或卸载前的清理。 */
    fun release() {}
}

/** prepare 的结果：成功，或带可展示原因的失败。 */
sealed class PrepareResult {
    object Ok : PrepareResult()

    data class Failed(val reason: String) : PrepareResult()

    val isOk: Boolean get() = this is Ok

    /** 失败原因；成功时为空串。 */
    val reasonOrNull: String? get() = (this as? Failed)?.reason
}

/** 分类对外能力的公共标记。 */
interface ExtensionCapability {
    val categoryId: String
}
