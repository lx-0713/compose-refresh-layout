package com.lx.compose.refresh

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 核心手势拦截与路由分发器。
 * 
 * 该连接器实现 [NestedScrollConnection] 接口，负责在滚动事件流的 Pre 与 Post 阶段
 * 拦截手势能量，并将其重定向为边界外的物理阻尼平移效果。
 *
 * 核心架构特征：
 * 1. **双端泛型适配**：动态将二维手势向量降维为主轴一维向量，实现单套逻辑兼容横/纵向排版。
 * 2. **无缝预加载机制**：触底拉拽阶段，当加载视图的可见度达到特定阈值时，提前触发数据请求，消除用户等待感。
 * 3. **终态悬停反馈**：数据穷尽状态下（`noMoreData == true`），解除阻尼约束实现 1:1 线性跟随，
 *    并在释放手势时将其停靠于视口边界，提供原生的沉浸式无数据提示体验。
 */
internal class SmartRefreshNestedScrollConnection(
    private val state: SmartSwipeRefreshState,
    private val orientation: Orientation,
    private val containerSize: Int,
    private val scope: CoroutineScope,
    private val onRefresh: () -> Unit,
    private val onLoadMore: () -> Unit
) : NestedScrollConnection {

    // --- 向量转换辅助扩展函数 ---
    // 使得核心计算逻辑不用去繁琐地判断当前是处于 X 轴还是 Y 轴的滚动
    private fun Offset.mainAxis(): Float = if (orientation == Orientation.Vertical) this.y else this.x
    private fun Velocity.mainAxis(): Float = if (orientation == Orientation.Vertical) this.y else this.x
    private fun Float.toOffset(): Offset = if (orientation == Orientation.Vertical) Offset(0f, this) else Offset(this, 0f)
    private fun Float.toVelocity(): Velocity = if (orientation == Orientation.Vertical) Velocity(0f, this) else Velocity(this, 0f)

    /**
     * 第一防线：在子视图（如列表）滚动**之前**触发。
     * 主要目的是：如果容器目前处于被拉开的状态，用户反向推动列表时，我们应该优先把偏移的容器推回原位，而不是让列表自己内部滚动。
     */
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val availableAxis = available.mainAxis()
        return when {
            // Header 已经被拉出（indicatorOffset > 0），用户正在向上推试图收起它
            availableAxis < 0 && state.indicatorOffset > 0 -> {
                val delta = (availableAxis * state.stickinessLevel).coerceAtLeast(-state.indicatorOffset)
                state.indicatorOffset += delta
                (delta / state.stickinessLevel).toOffset() // 告知系统：这段距离我消费了
            }
            // Footer 已经被拉出，或者 Content 处于驻留状态，用户正在向下推试图收起它们
            availableAxis > 0 && (state.indicatorOffset < 0f || (state.isContentOffsetDecoupled && state.decoupledContentOffset < 0f)) -> {
                var consumed = 0f

                // 1. 收起 Footer
                if (state.indicatorOffset < 0f) {
                    val stickiness = if (state.noMoreData) 1f else state.stickinessLevel
                    val delta = (availableAxis * stickiness).coerceAtMost(-state.indicatorOffset)
                    state.indicatorOffset += delta
                    // 物理约束：当 Footer 正在收起时，为保证解耦的 Content 与 Footer 相对位置静止，
                    // Content 必须同步向下平移精确的 delta 值！
                    if (state.isContentOffsetDecoupled) {
                        state.decoupledContentOffset += delta
                    }
                    consumed = maxOf(consumed, delta / stickiness)
                }

                // 计算第一阶段剩余的可用位移量
                val remainingAxis = availableAxis - consumed

                // 2. Footer 已经完全收起（或本来就收起了），但 Content 依然是解耦驻留状态，
                // 此时我们需要继续 1:1 跟手收起 Content。
                if (remainingAxis > 0f && state.indicatorOffset == 0f && state.isContentOffsetDecoupled && state.decoupledContentOffset < 0f) {
                    val delta = remainingAxis.coerceAtMost(-state.decoupledContentOffset)
                    state.decoupledContentOffset += delta
                    consumed += delta // 因为是 1:1 回收，直接消耗等量位移

                    // 完全归位后关闭平移解耦状态
                    if (state.decoupledContentOffset >= 0f) {
                        state.isContentOffsetDecoupled = false
                        state.decoupledContentOffset = 0f
                    }
                }

                consumed.toOffset()
            }
            else -> Offset.Zero // 不在边界外时，不拦截任何手势
        }
    }

    /**
     * 第二防线：在子视图滚动**到底/到顶**，并且产生了未消耗的溢出位移时触发。
     * 主要目的是：把溢出的、无法被列表消费的滚动力量转化为对外部容器的“拉扯阻尼拉长”效果。
     */
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        val availableAxis = available.mainAxis()
        return when {
            // 列表到达了顶部，用户还在使劲往下拉（开始拉出 Header）
            availableAxis > 0 && state.enableRefresh && state.headerBound > 0f && state.refreshFlag != SmartRefreshFlag.FINISHING -> {
                // 计算当前容许的最大形变边界。Fling 代表系统惯性滚动。
                val limit = if (source == NestedScrollSource.Fling) state.headerBound else containerSize / 2f
                val delta = (availableAxis * state.stickinessLevel).coerceAtMost(limit - state.indicatorOffset)
                
                state.indicatorOffset += delta

                if (state.refreshFlag == SmartRefreshFlag.IDLE && state.indicatorOffset > 0) {
                    state.refreshFlag = SmartRefreshFlag.PULLING
                }

                // 极端情况防御：如果是系统强劲的惯性滚到了顶部并且直接飞出阈值，直接触发刷新回调
                if (source == NestedScrollSource.Fling && state.indicatorOffset >= state.headerBound && state.refreshFlag != SmartRefreshFlag.REFRESHING) {
                    state.refreshFlag = SmartRefreshFlag.REFRESHING
                    state.indicatorOffset = state.headerBound
                    onRefresh()
                }
                availableAxis.toOffset()
            }

            // 列表到达了底部，用户还在使劲往上提（开始拉出 Footer）
            availableAxis < 0 && state.enableLoadMore && state.footerBound > 0f && state.loadMoreFlag != SmartRefreshFlag.FINISHING -> {
                val limit = state.footerBound
                // 物理特性：到达底部时若已无更多数据，释放阻尼系数（1f）以实现顺滑的跟手交互
                val stickiness = if (state.noMoreData) 1f else state.stickinessLevel
                val delta = (availableAxis * stickiness).coerceAtLeast(-limit - state.indicatorOffset)

                state.indicatorOffset += delta
                // 物理同步：如果 Content 处于解耦驻留状态，必须同步上移，保证 Footer 绝对不会追尾遮挡 Content
                if (state.isContentOffsetDecoupled) {
                    state.decoupledContentOffset += delta
                }

                // 预加载策略：footer 完全可见（100%）时触发加载请求
                if (!state.noMoreData) {
                    if (state.loadMoreFlag == SmartRefreshFlag.IDLE && state.indicatorOffset < 0) {
                        state.loadMoreFlag = SmartRefreshFlag.PULLING
                    }
                    if (state.indicatorOffset <= -state.footerBound && state.loadMoreFlag != SmartRefreshFlag.REFRESHING) {
                        state.loadMoreFlag = SmartRefreshFlag.REFRESHING
                        onLoadMore()
                    }
                }

                (delta / stickiness).toOffset()
            }
            else -> Offset.Zero
        }
    }

    /**
     * 手指离开屏幕产生滑动惯性（Fling）前的最后一道拦截。
     * 主要目的是：判断当前越界程度是否满足刷新要求。如果是，拦截该惯性并在悬停位执行动画，同时消费该手势的动能以避免内部列表失控漂移。
     */
    override suspend fun onPreFling(available: Velocity): Velocity {
        val offset = state.indicatorOffset
        val headerOver = state.headerBound > 0f && offset >= state.headerBound
        val footerOver = state.footerBound > 0f && offset <= -state.footerBound

        // 情境1：如果状态本身已经是 Refreshing，松手后确保立刻稳稳地停靠在阈值处
        if (state.refreshFlag == SmartRefreshFlag.REFRESHING) {
            if (headerOver) scope.launch { state.animateOffsetTo(state.headerBound) }
            return available.mainAxis().toVelocity()
        } else if (state.loadMoreFlag == SmartRefreshFlag.REFRESHING) {
            // 溢出约束：如果用户在 REFRESHING 期间继续过度拉动，则在松手时让其平滑回弹到 -footerBound。
            if (offset < -state.footerBound) {
                scope.launch { state.animateOffsetTo(-state.footerBound) }
                return available.mainAxis().toVelocity()
            }
            // 手势释放时，如果 Indicator 处于 0 到 -footerBound 之间，
            // 直接让其停留在当前位置（返回 Velocity.Zero），等待用户接下来的操作，而不是强制弹回
            if (offset < 0f && offset > -state.footerBound) {
                return Velocity.Zero
            }
        }

        return when {
            // 情境2：用户用力拉过了 Header 的阈值，松手 -> 开始真正意义上的刷新
            headerOver && state.refreshFlag != SmartRefreshFlag.FINISHING -> {
                state.refreshFlag = SmartRefreshFlag.REFRESHING
                scope.launch { state.animateOffsetTo(state.headerBound) }
                onRefresh()
                available.mainAxis().toVelocity()
            }
            // 情境3：用户用力拉过了 Footer 的阈值，松手 -> 开始上拉加载
            footerOver && !state.noMoreData && state.loadMoreFlag != SmartRefreshFlag.FINISHING -> {
                state.loadMoreFlag = SmartRefreshFlag.REFRESHING
                scope.launch { state.animateOffsetTo(-state.footerBound) }
                onLoadMore()
                available.mainAxis().toVelocity()
            }
            // 终态物理反馈：如果是没有更多数据的情况，根据物理甩动方向顺势归位。
            // 这避免了悬停层异常弹跳。
            state.noMoreData && offset < 0f -> {
                val axisVel = available.mainAxis()
                if (axisVel < 0) {
                    // 向上甩出去，直接停靠展开
                    scope.launch { state.animateOffsetTo(-state.footerBound) }
                    available.mainAxis().toVelocity()
                } else if (axisVel > 0) {
                    // 向下甩，直接顺势隐藏收起
                    scope.launch { state.animateOffsetTo(0f) }
                    available.mainAxis().toVelocity()
                } else {
                    Velocity.Zero
                }
            }
            else -> Velocity.Zero
        }
    }

    /**
     * 处理手势释放后未达到触发阈值而需要收回（弹回 0f）的复位逻辑。
     */
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        // 如果拉了一半没有越过刷新阈值，放弃刷新操作并归位隐藏
        if (state.refreshFlag != SmartRefreshFlag.REFRESHING && state.indicatorOffset > 0f) {
            state.refreshFlag = SmartRefreshFlag.IDLE
            scope.launch { state.animateOffsetTo(0f) }
        }
        
        // 处理底部的回弹逻辑
        if (state.loadMoreFlag != SmartRefreshFlag.REFRESHING && state.indicatorOffset < 0f) {
            if (state.noMoreData) {
                // 无数据回弹：在没有更多数据的前提下静止松手。若露出大于一半就悬浮停靠，否则收起。
                if (state.indicatorOffset < -state.footerBound / 2) {
                    scope.launch { state.animateOffsetTo(-state.footerBound) }
                } else {
                    scope.launch { state.animateOffsetTo(0f) }
                }
            } else {
                // 普通加载态没有达到阈值：直接回弹并取消动作。
                state.loadMoreFlag = SmartRefreshFlag.IDLE
                scope.launch { state.animateOffsetTo(0f) }
            }
        }
        return Velocity.Zero
    }
}
