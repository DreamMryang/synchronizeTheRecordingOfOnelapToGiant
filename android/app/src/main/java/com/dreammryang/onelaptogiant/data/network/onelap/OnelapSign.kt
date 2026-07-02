package com.dreammryang.onelaptogiant.data.network.onelap

import java.security.MessageDigest
import java.util.UUID

object OnelapSign {
    const val SIGN_KEY = "fe9f8382418fcdeb136461cac6acae7b"

    fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun nonce(uuid: String = UUID.randomUUID().toString()): String =
        uuid.replace("-", "").takeLast(16)

    fun sign(account: String, passwordMd5: String, nonce: String, timestamp: Long): String =
        md5Hex("account=$account&nonce=$nonce&password=$passwordMd5&timestamp=$timestamp&key=$SIGN_KEY")
}
