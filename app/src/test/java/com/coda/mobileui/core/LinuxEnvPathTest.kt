package com.coda.mobileui.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 解包链接目标校验的回归测试。
 *
 * 对应安全修复：归档里的符号链接一旦指向解包目录之外，装进 rootfs 就等于
 * 给容器留了一条写到宿主机的通道。这里只做词法归一，允许相对链接但拒绝越界。
 */
class LinuxEnvPathTest {
    private val dest = File("/tmp/coda-test-rootfs")

    @Test
    fun rebasesAbsoluteTargetsOntoGuestRoot() {
        // #767：Alpine 的 /bin/sh -> /bin/busybox 是 rootfs 标准链接，
        // proot 下按 guest 根解析，必须放行而不是静默跳过。
        assertTrue(LinuxEnv.symlinkStaysInside(dest, "bin/sh", "/bin/busybox"))
        assertTrue(LinuxEnv.symlinkStaysInside(dest, "etc/resolv.conf", "/etc/resolv.conf"))
    }
    @Test
    fun rejectsAbsoluteTargetsEscapingGuestRoot() {
        assertFalse(LinuxEnv.symlinkStaysInside(dest, "etc/link", "/../../data/local/tmp/x"))
        assertFalse(LinuxEnv.symlinkStaysInside(dest, "link", "/.."))
    }

    @Test
    fun rejectsTargetsEscapingThroughParentDirectories() {
        assertFalse(
            LinuxEnv.symlinkStaysInside(dest, "etc/link", "../../../../data/local/tmp/x"),
        )
    }

    @Test
    fun allowsRelativeTargetInsideSameDirectory() {
        assertTrue(LinuxEnv.symlinkStaysInside(dest, "usr/lib/libc.so", "libc.so.6"))
    }

    @Test
    fun allowsRelativeTargetAcrossSiblingDirectories() {
        assertTrue(LinuxEnv.symlinkStaysInside(dest, "bin/sh", "../usr/bin/busybox"))
    }

    @Test
    fun allowsTargetPointingAtRootItself() {
        assertTrue(LinuxEnv.symlinkStaysInside(dest, "etc/link", ".."))
    }

    @Test
    fun rejectsTrailingParentThatResolvesToHostRoot() {
        assertFalse(LinuxEnv.symlinkStaysInside(dest, "link", "../../.."))
    }

    @Test
    fun rejectsEmptyTarget() {
        assertFalse(LinuxEnv.symlinkStaysInside(dest, "etc/link", ""))
    }
}