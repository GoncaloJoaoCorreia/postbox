package com.postbox.shared.storage

expect abstract class PlatformContext

expect fun createTokenStorage(context: PlatformContext): TokenStorage

expect fun createInstanceSessionStore(context: PlatformContext): InstanceSessionStore
