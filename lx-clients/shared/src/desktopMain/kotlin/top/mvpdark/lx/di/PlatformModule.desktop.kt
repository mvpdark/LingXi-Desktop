package top.mvpdark.lx.di

import org.koin.dsl.module
import top.mvpdark.lx.core.network.PlatformContext
import top.mvpdark.lx.core.network.TokenStore
import top.mvpdark.lx.data.local.LocalMessageStore

/**
 * Desktop 平台 Koin 模块：提供 [TokenStore]。
 */
val platformModule = module {
    single { PlatformContext() }
    single { TokenStore(get()) }
    single { LocalMessageStore(get()) }
}
