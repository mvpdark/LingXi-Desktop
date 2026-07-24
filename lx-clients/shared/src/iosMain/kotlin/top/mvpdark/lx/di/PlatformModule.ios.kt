package top.mvpdark.lx.di

import org.koin.dsl.module
import top.mvpdark.lx.core.network.PlatformContext
import top.mvpdark.lx.core.network.TokenStore
import top.mvpdark.lx.core.util.ImageSaver
import top.mvpdark.lx.data.local.LocalMessageStore

/**
 * iOS 平台 Koin 模块：提供 [TokenStore]、[LocalMessageStore]、[ImageSaver]。
 *
 * 与 Desktop 端一致，[PlatformContext] 为空占位（iOS TokenStore 使用
 * NSUserDefaults.standardUserDefaults，无需额外上下文）。
 *
 * [LocalMessageStore] 基于 NSDocumentDirectory 文件系统实现，
 * [ImageSaver] 基于 PHPhotoLibrary 保存到系统相册。
 */
val platformModule = module {
    single { PlatformContext() }
    single { TokenStore(get()) }
    single { LocalMessageStore(get()) }
    single { ImageSaver(get()) }
}
