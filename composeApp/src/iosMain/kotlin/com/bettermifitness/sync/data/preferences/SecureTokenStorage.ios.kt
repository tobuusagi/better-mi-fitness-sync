package com.bettermifitness.sync.data.preferences

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus

/**
 * iOS implementation using Keychain (kSecClassGenericPassword).
 * Tokens never touch NSUserDefaults or plaintext files.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class SecureTokenStorage actual constructor() {
    private val service = "com.bettermifitness.sync.tokens"

    private fun queryFor(key: String): NSMutableDictionary {
        val q = NSMutableDictionary()
        q[kSecClass] = kSecClassGenericPassword
        q[kSecAttrService] = service
        q[kSecAttrAccount] = key
        return q
    }

    actual fun get(key: String): String? = memScoped {
        val q = queryFor(key)
        q[kSecMatchLimit] = kSecMatchLimitOne
        q[kSecReturnData] = true
        val result: CFTypeRefVar = alloc()
        val status: OSStatus = SecItemCopyMatching(q as CFDictionaryRef, result.ptr)
        if (status != errSecSuccess) return null
        val data = result.value as? NSData ?: return null
        return NSString.create(data, platform.Foundation.NSUTF8StringEncoding) as String?
    }

    actual fun put(key: String, value: String) {
        // Delete existing first (SecItemAdd fails on duplicate)
        remove(key)
        memScoped {
            val q = queryFor(key)
            val nsValue = value as NSString
            val data = nsValue.dataUsingEncoding(platform.Foundation.NSUTF8StringEncoding)!!
            q[kSecValueData] = data
            SecItemAdd(q as CFDictionaryRef, null)
        }
    }

    actual fun remove(key: String) = memScoped {
        SecItemDelete(queryFor(key) as CFDictionaryRef)
        Unit
    }

    actual fun clear() {
        // Keychain has no bulk delete by service; callers remove known keys.
        // Kept as no-op to satisfy the interface; use remove() per key.
    }
}
