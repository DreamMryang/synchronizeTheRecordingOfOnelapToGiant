package com.dreammryang.onelaptogiant.data.network.onelap

import org.junit.Assert.assertEquals
import org.junit.Test

class OnelapSignTest {

    @Test
    fun `md5Hex 输出 32 位小写十六进制`() {
        assertEquals("32250170a0dca92d53ec9624f336ca24", OnelapSign.md5Hex("pass123"))
    }

    @Test
    fun `nonce 为 UUID 去横线后取末 16 位`() {
        assertEquals(
            "a716446655440000",
            OnelapSign.nonce("550e8400-e29b-41d4-a716-446655440000"),
        )
    }

    @Test
    fun `nonce 无参时随机且长度 16`() {
        assertEquals(16, OnelapSign.nonce().length)
    }

    @Test
    fun `sign 拼串顺序与桌面版一致`() {
        // 期望值 = md5("account=13800138000&nonce=abcdef0123456789&password=32250170a0dca92d53ec9624f336ca24&timestamp=1751443200&key=fe9f8382418fcdeb136461cac6acae7b")
        val sign = OnelapSign.sign(
            account = "13800138000",
            passwordMd5 = OnelapSign.md5Hex("pass123"),
            nonce = "abcdef0123456789",
            timestamp = 1751443200L,
        )
        assertEquals("d68287cd4b8b6d4af22adf1691029ea7", sign)
    }
}
