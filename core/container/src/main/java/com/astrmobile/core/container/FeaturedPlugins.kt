package com.astrmobile.core.container

/**
 * 精选插件。
 *
 * 市场里有 1300 多个插件，按"记忆""人格"搜出来几十个，光看描述分不出
 * 哪个能在手机上跑。这里只收几个**确认能在容器里装起来**的，并把代价
 * 写在明面上——这些插件都会额外调模型，不是免费的。
 *
 * 判断标准很简单：能不加外部服务、不加数据库、装完就能加载成功的。
 * 依赖 Milvus / PostgreSQL 的那类一律不收，手机上跑不动。
 */
data class FeaturedPlugin(
    /** GitHub 的 owner/repo，安装时按这个下载。 */
    val repo: String,
    val displayName: String,
    val summary: String,
    /** 为什么值得装，以及它跟宣传语不一样的地方。 */
    val note: String,
    /** 有没有 requirements.txt，需要额外装依赖。 */
    val needsDeps: Boolean,
    /** 额外成本。宁可说得难听，也别让人装完才发现账单翻倍。 */
    val costNote: String,
    /** 需要先在 AstrBot 里配好的前置条件，null 表示没有。 */
    val prerequisite: String? = null,
)

object FeaturedPlugins {

    val all: List<FeaturedPlugin> = listOf(
        FeaturedPlugin(
            repo = "Renyus/astrbot_plugin_self_evolution",
            displayName = "自我进化",
            summary = "给它一套「活着」的状态：能量、心情、社交需求、饱腹感",
            note = "状态推演是本地规则，不烧 token。它会把这些当作人格叙事注入，" +
                "所以回复会带上下文情绪，而不是每句都从零开始。",
            needsDeps = false,
            costNote = "群聊分析会额外调模型，频率可以在插件设置里调低",
        ),
        FeaturedPlugin(
            repo = "exynos967/astrbot_plugin_memorix",
            displayName = "Memorix 记忆",
            summary = "长期记忆：SQLite + 中文分词检索，不配 embedding 也能用",
            note = "零外部服务，这是它比同类插件适合手机的地方。开向量检索效果更好，" +
                "但要额外配一个 Embedding 模型。",
            needsDeps = true,
            costNote = "检索结果会注入 prompt，输入 token 会变多",
        ),
        FeaturedPlugin(
            repo = "DBJD-CR/astrbot_plugin_proactive_chat",
            displayName = "主动消息",
            summary = "让它主动开口，带免打扰时段和情绪感知",
            note = "这是「像人」最明显的一步：会主动找人说话，而不是等着被 @。",
            needsDeps = false,
            costNote = "每条主动消息都是一次完整调用，频率一定要设低",
        ),
        FeaturedPlugin(
            repo = "lxfight-s-Astrbot-Plugins/astrbot_plugin_livingmemory",
            displayName = "LivingMemory",
            summary = "记忆生命周期管理：记住、淡忘、归纳",
            note = "功能比 Memorix 完整，但它**必须**先有 Embedding Provider，" +
                "否则向量索引初始化会直接失败。",
            needsDeps = false,
            costNote = "每次写入和召回都要调 embedding，按量计费",
            prerequisite = "先在「模型」页配好一个支持 embedding 的模型",
        ),
    )

    fun byRepo(repo: String): FeaturedPlugin? =
        all.firstOrNull { it.repo.equals(repo, ignoreCase = true) }

    /** `owner/repo` 里的仓库名，也就是插件装好后的目录名。 */
    fun dirNameOf(repo: String): String = repo.substringAfterLast('/').substringBefore(".git")

    /** 插件名里只允许这些字符：它会被拼进容器内的命令。 */
    fun isSafeDirName(name: String): Boolean =
        name.isNotBlank() && name.length <= 100 && name.matches(Regex("[A-Za-z0-9._-]+"))
}
