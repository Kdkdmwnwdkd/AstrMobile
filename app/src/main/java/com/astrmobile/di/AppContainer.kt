package com.astrmobile.di

import android.content.Context
import com.astrmobile.core.container.Bootstrapper
import com.astrmobile.core.container.ContainerController
import com.astrmobile.core.container.ContainerPaths
import com.astrmobile.core.data.SettingsRepository
import java.io.File

/**
 * 手动依赖容器。
 *
 * 这里刻意不使用 Hilt：本项目只有一个 Application、一个 Activity、
 * 三个长生命周期对象，引入注解处理器只会拉长构建时间并增加版本耦合。
 * 依赖关系保持显式，反而更符合“可维护的骨架”这一目标。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val paths: ContainerPaths = ContainerPaths(
        filesDir = appContext.filesDir,
        nativeLibDir = File(appContext.applicationInfo.nativeLibraryDir),
    )

    val settings: SettingsRepository = SettingsRepository(appContext)

    val controller: ContainerController = ContainerController(appContext, paths)

    val bootstrapper: Bootstrapper = Bootstrapper(appContext, paths)
}
