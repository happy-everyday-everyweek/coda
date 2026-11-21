package com.coda.mobileui.core

/**
 * 导入备份的两处决策。
 *
 * 单独抽出来是为了把两条约束固定下来：坏包不能停运行环境，导入结束必须重启应用。
 * 两者都影响用户能不能继续用对话与终端，不该散落在界面代码里靠记忆维持。
 */
internal object BackupImportFlow {
    /** 预检失败时绝不能停运行环境：一个坏包不该让对话、工具、终端全部不可用。 */
    fun shouldStopRuntime(precheckFailure: String?): Boolean = precheckFailure == null

    /**
     * 导入结束是否需要重启应用。
     *
     * 成功要重启：覆盖的是设置、数据库与 Linux 环境，进程内还留着旧的 SharedPreferences
     * 与已打开的数据库连接。失败同样要重启：失败往往发生在已经写入若干条目之后，
     * 停在半途的进程里核心是停的、界面还按旧值显示。
     *
     * 也就是说只要运行环境已经被停过（预检通过才会停），无论结果如何都得重启。
     */
    fun mustRelaunch(runtimeStopped: Boolean): Boolean = runtimeStopped
}
