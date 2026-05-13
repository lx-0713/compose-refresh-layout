package com.lx.compose.refresh

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/**
 * 刷新容器的核心状态机与数据源控制中心（与具体视图实现完全解耦）。
 *
 * 该类作为唯一的数据源 (Source of Truth)，控制着刷新组件的所有核心能力：
 * 1. **位移记录**：记录物理偏移量 [indicatorOffset] 供外层图形渲染层 (`graphicsLayer`) 使用。
 * 2. **状态流转**：维护 [refreshFlag] 与 [loadMoreFlag] 两条独立的状态链路，避免并发冲突。
 * 3. **边界测量**：接收 `Layout` 阶段传入的首尾高度 [headerBound] / [footerBound]，将其作为动画弹跳和阻尼的阈值依据。
 *
 * @param stickinessLevel 弹性阻尼系数（0~1）。举例：0.5f 代表用户手指滑动 100px，UI 实际移动 50px。
 * @param enableRefresh 是否开启全局的下拉刷新能力。
 * @param enableLoadMore 是否开启全局的上拉加载能力。
 */
@Stable
class SmartSwipeRefreshState(
    stickinessLevel: Float = 0.5f,
    enableRefresh: Boolean = true,
    enableLoadMore: Boolean = true
) {
    /** 阻尼系数：限制并约束了拖拽的顺滑感。可通过外部动态修改。 */
    private var _stickinessLevel by mutableFloatStateOf(stickinessLevel.coerceIn(0.1f, 1f))
    var stickinessLevel: Float
        get() = _stickinessLevel
        set(value) {
            _stickinessLevel = value.coerceIn(0.1f, 1f)
        }

    /** 全局开关：是否允许下拉刷新 */
    var enableRefresh by mutableStateOf(enableRefresh)

    /** 全局开关：是否允许上拉加载 */
    var enableLoadMore by mutableStateOf(enableLoadMore)

    /**
     * 数据边界标识：指示数据流是否已达末尾。
     *
     * 当为 `true` 时，底层引擎将释放悬停阻尼约束，并提供沉浸式的无数据终态驻留反馈。
     * 该状态 **仅应通过 [finishLoadMore] 来变更**，不与下拉刷新逻辑耦合。
     */
    var noMoreData by mutableStateOf(false)
        internal set

    // ─────────────────────────────────────────────────────────────────────
    // 【核心：单一 Animatable 控制动画调度】
    //
    // 设计原理：在复杂的嵌套滑动与异步加载场景中，位移动画极易发生并发调用。
    // 为确保动画链路的绝对唯一性与平滑度，底层采用全局单一持久化的 Animatable 实例。
    //
    // 调度策略：
    // 1. `animateOffsetTo` 被调用时，会先通过 `snapTo(currentValue)` 挂起并取消正在执行的其他动画。
    // 2. 随后通过 `animateTo` 无缝衔接至新目标位置，确保“新调度永远覆盖旧调度”，杜绝多协程抢占问题。
    // 3. 手指拖拽时的实时位移（手势跟手）直接写入 `indicatorOffset`。由于拖拽过程不涉及挂起动画，
    //    这两套机制（动画调度与物理手势驱动）在设计上完全解耦，互不干扰。
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 当前指示器的实际物理偏移量（px）。
     * - 正值：下拉刷新方向（Header 被拉出）
     * - 负值：上拉加载方向（Footer 被拉出）
     *
     * 手势跟手时直接赋值（同步，非 suspend）；
     * 动画时通过 [animateOffsetTo] 经由 [_anim] 驱动。
     */
    var indicatorOffset by mutableFloatStateOf(0f)
        internal set

    /**
     * 单一持久化 Animatable，驱动所有平滑过渡动画。
     * 不对外暴露，所有外部操作通过 [animateOffsetTo] 进行。
     */
    private val _anim = Animatable(0f)

    /** 顶部下拉刷新的有限状态机 (FSM) 标识 */
    private var _refreshFlag by mutableStateOf(SmartRefreshFlag.IDLE)
    var refreshFlag: SmartRefreshFlag
        get() = _refreshFlag
        internal set(value) {
            _refreshFlag = value
            // 核心自动化设计：一旦触发下拉刷新（无论是手势还是 autoRefresh），
            // 必然属于重新加载首页数据，此时必须自动重置“没有更多数据”的标记，
            // 从而使后续上拉加载重新生效。
            if (value == SmartRefreshFlag.REFRESHING) {
                noMoreData = false
            }
        }

    /** 底部上拉加载的有限状态机 (FSM) 标识 */
    var loadMoreFlag by mutableStateOf(SmartRefreshFlag.IDLE)
        internal set

    /** 顶部 Header 的实际测量大小（高/宽）。由外部 Layout 测量后注入。 */
    internal var headerBound by mutableFloatStateOf(0f)
    internal var footerBound by mutableFloatStateOf(0f)

    // ─────────────────────────────────────────────────────────────────────
    // 【视口解耦与增量渲染架构】
    // 
    // 在异步加载新数据时，为保障用户的滚动坐标系不发生跳跃，必须实现视口的解耦分离。
    // 允许 Content 驻留在当前可视层级（decoupledContentOffset），同时外层容器
    // 挂载点（indicatorOffset）继续延伸，以承载新装载的数据节点。
    // ─────────────────────────────────────────────────────────────────────
    var isContentOffsetDecoupled by mutableStateOf(false)
        internal set

    var decoupledContentOffset by mutableFloatStateOf(0f)
        internal set

    /**
     * 增量物理空间计算属性。
     * 
     * 通过恒等式：`H + addedHeight + decoupledContentOffset == H + indicatorOffset`
     * 推导出边界扩容公式：`addedHeight = indicatorOffset - decoupledContentOffset`。
     * 
     * 该计算属性采用 `derivedStateOf` 封装，有效阻隔了由于高频位移导致的重绘渗透，
     * 仅当差值（物理边界）发生实质改变时，才触发 Compose 引擎的重测量。
     */
    val addedHeight: Int by derivedStateOf {
        if (!isContentOffsetDecoupled) 0
        else (indicatorOffset - decoupledContentOffset).coerceAtLeast(0f).roundToInt()
    }

    val currentContentOffset: Float
        get() {
            if (!isContentOffsetDecoupled) return indicatorOffset
            return decoupledContentOffset
        }

    /**
     * 将 [indicatorOffset] 平滑动画到目标值。
     *
     * 采用 spring 弹性阻尼曲线，替代线性动画，以获得媲美 iOS 阻尼回弹的物理真实感。
     */
    internal suspend fun animateOffsetTo(target: Float) {
        _anim.snapTo(indicatorOffset)
        _anim.animateTo(
            targetValue = target,
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
            )
        ) {
            val delta = value - indicatorOffset
            indicatorOffset = value
            if (isContentOffsetDecoupled) {
                decoupledContentOffset += delta
            }
        }
    }

    /**
     * 无动画瞬间切断并归位 [indicatorOffset]。
     * 用于加载完成后瞬间移除 Footer 并无缝拼接新数据。
     */
    internal suspend fun snapOffsetTo(target: Float) {
        _anim.snapTo(target)
        indicatorOffset = target
    }

    /**
     * 触发下拉刷新结束状态。
     *
     * 该方法 **不会干预 [noMoreData] 的状态**，
     * 避免调用方在同一接口回调中先后调用 [finishLoadMore] 与 [finishRefresh] 时，
     * 后者的默认参数无意间覆盖前者已设置 of 无更多数据标记。
     *
     * 如需在下拉刷新完成后重置无更多数据状态，请在业务层显式调用
     * `finishLoadMore(noMoreData = false)`。
     */
    /**
     * 触发下拉刷新结束状态。
     */
    private suspend fun finishRefresh() {
        if (refreshFlag == SmartRefreshFlag.IDLE) return
        refreshFlag = SmartRefreshFlag.FINISHING
        animateOffsetTo(0f)
        refreshFlag = SmartRefreshFlag.IDLE
    }

    /**
     * 统一结束当前活跃的刷新/加载操作。
     *
     * 当下拉刷新与上拉加载共用同一接口时，接口回调中无法区分本次是哪种操作触发的，
     * 此时可统一调用本方法，内部会自动识别当前活跃的操作并处理对应的收尾逻辑：
     * - 若当前是**下拉刷新**：执行 Header 回弹动画，[noMoreData] 同步更新。
     * - 若当前是**上拉加载**：根据 [noMoreData] 决定 Footer 的动画归宿。
     *
     * **推荐用法：**
     * ```kotlin
     * onRefresh = { scope.launch { delay(1000); state.finish() } }
     * onLoadMore = { scope.launch { delay(1000); state.finish(noMoreData = true) } }
     * ```
     *
     * @param noMoreData 是否已无更多数据（上拉加载/下拉刷新均可传入）。
     */
    suspend fun finish(noMoreData: Boolean = false) {
        // 先处理下拉刷新（如果活跃），再处理上拉加载（如果活跃）；
        // 两个 flag 互斥，因此始终只有一个分支真正执行动画。
        finishRefresh()
        finishLoadMore(noMoreData)
    }

    /**
     * 触发上拉加载结束状态。
     * 基于解耦渲染架构，实现 Footer 瞬间移除与新数据无缝衔接。
     */
    private suspend fun finishLoadMore(noMoreData: Boolean = false) {
        this.noMoreData = noMoreData
        if (loadMoreFlag == SmartRefreshFlag.IDLE) return

        loadMoreFlag = SmartRefreshFlag.FINISHING

        if (!noMoreData && indicatorOffset < 0f) {
            if (!isContentOffsetDecoupled) {
                // 初始化解耦状态，锁定当前的物理平移量
                isContentOffsetDecoupled = true
                decoupledContentOffset = indicatorOffset
            }
        }

        if (noMoreData && footerBound > 0f) {
            // 如果用户已经主动向下划走隐藏了 Footer，不强行弹出干扰阅读，让其保持隐藏
            if (indicatorOffset >= -0.5f) {
                snapOffsetTo(0f)
            } else {
                animateOffsetTo(-footerBound)
            }
        } else {
            // 数据成功加载，瞬间归位 Footer，由于 addedHeight 的数学约束会自动填补空间，
            // 列表底部将产生完美、无跳动的替换效果
            snapOffsetTo(0f)
        }

        loadMoreFlag = SmartRefreshFlag.IDLE
    }

    /**
     * 由程序主动触发的上拉加载。
     * 调用后底部 Footer 会自动弹出，并触发绑定的 onLoadMore 回调。
     */
    suspend fun autoLoadMore() {
        if (noMoreData || loadMoreFlag != SmartRefreshFlag.IDLE || footerBound <= 0f) return
        loadMoreFlag = SmartRefreshFlag.REFRESHING
        animateOffsetTo(-footerBound)
    }

    /**
     * 由程序主动触发的下拉刷新。
     * 调用后顶部 Header 会自动弹出，并触发绑定的 onRefresh 回调。
     */
    suspend fun autoRefresh() {
        if (refreshFlag != SmartRefreshFlag.IDLE || headerBound <= 0f) return
        refreshFlag = SmartRefreshFlag.REFRESHING
        animateOffsetTo(headerBound)
    }
}

/**
 * 构建并持久化一个 [SmartSwipeRefreshState] 实例，与所在组件的生命周期绑定。
 *
 * @param stickinessLevel 弹性阻尼系数，控制划动手感
 * @param enableRefresh 是否开启下拉刷新支持
 * @param enableLoadMore 是否开启上拉加载支持
 */
@Composable
fun rememberSmartSwipeRefreshState(
    stickinessLevel: Float = 0.5f,
    enableRefresh: Boolean = true,
    enableLoadMore: Boolean = true
): SmartSwipeRefreshState {
    return remember {
        SmartSwipeRefreshState(
            stickinessLevel = stickinessLevel,
            enableRefresh = enableRefresh,
            enableLoadMore = enableLoadMore
        )
    }
}
