package com.lx.compose.refresh

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 核心刷新容器核心层（基于原生 Layout 与硬件加速的复合渲染引擎）。
 *
 * 作为一个具备泛用性与极简状态机的高级嵌套滑动容器，其核心架构特性如下：
 * 1. **多维排版兼容**：通过 [Orientation] 动态重定向约束与平移向量，完美支持纵向（Vertical）与横向（Horizontal）的滚动场景。
 * 2. **无侵入式解耦**：不依赖或侵入任何具体的列表实现，通过标准的 [NestedScrollConnection] 拦截手势，
 *    对任意支持嵌套滚动的子组件（如 `LazyColumn`, `LazyRow`, `HorizontalPager` 等）实现透明包裹。
 * 3. **纯净硬件加速**：摒弃传统改变约束导致的高频 UI 重排（Relayout），全程依靠 [graphicsLayer] 的图层平移
 *    处理交互位移，将 CPU 开销降至极低，实现零卡顿的顺滑手势跟随。
 * 4. **预布局防闪动**：在底层的 [Layout] 测量阶段优先对首尾装饰层完成布局测量，彻底消除首帧渲染时的组件闪烁问题。
 *
 * @param modifier 外部修饰符
 * @param state 核心状态机引擎，控制并追踪当前的阻尼平移量与生命周期状态
 * @param orientation 排版方向。决定了手势拦截的作用轴以及 Header/Footer 的挂载方位
 * @param onRefresh 下拉刷新（或向右拖拽）触发的异步回调
 * @param onLoadMore 上拉加载（或向左拖拽）触发的异步回调
 * @param noMoreDataText 业务层高度定制的“无更多数据”文案，用于替代默认字符串
 * @param header 自定义头部渲染器，默认提供了一个标准的旋转指示器 [RefreshHeader]
 * @param footer 自定义尾部渲染器，默认提供了一个具备数据穷尽停靠特效的 [RefreshFooter]
 * @param content 支持嵌套滚动事件分发的内容主体
 */
@Composable
fun SmartSwipeRefresh(
    modifier: Modifier = Modifier,
    state: SmartSwipeRefreshState,
    orientation: Orientation = Orientation.Vertical,
    onRefresh: (() -> Unit)? = null,
    onLoadMore: (() -> Unit)? = null,
    noMoreDataText: String? = null,
    header: @Composable () -> Unit = { 
        RefreshHeader(flag = state.refreshFlag, orientation = orientation) 
    },
    footer: @Composable () -> Unit = {
        RefreshFooter(
            flag = state.loadMoreFlag,
            noMoreData = state.noMoreData,
            orientation = orientation,
            noMoreText = noMoreDataText
        )
    },
    content: @Composable () -> Unit
) {
    val isVertical = orientation == Orientation.Vertical
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = modifier) {
        val containerSize = if (isVertical) constraints.maxHeight else constraints.maxWidth

        // 绑定无状态耦合的嵌套滑动连接器
        val connection = remember(state, orientation, containerSize, scope, onRefresh, onLoadMore) {
            SmartRefreshNestedScrollConnection(
                state = state,
                orientation = orientation,
                containerSize = containerSize,
                scope = scope,
                onRefresh = { onRefresh?.invoke() },
                onLoadMore = { onLoadMore?.invoke() }
            )
        }

        Layout(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(connection)
                .clipToBounds(),
            content = {
                // 通过 layoutId 为子组件打标，便于在测量与放置阶段进行精准识别
                Box(modifier = Modifier.layoutId("header")) { header() }
                Box(modifier = Modifier.layoutId("content")) { content() }
                Box(modifier = Modifier.layoutId("footer")) { footer() }
            }
        ) { measurables, constraints ->

            val safeConstraints = constraints.copy(minWidth = 0, minHeight = 0)
            val headerPlaceable = measurables.first { it.layoutId == "header" }.measure(safeConstraints)
            val footerPlaceable = measurables.first { it.layoutId == "footer" }.measure(safeConstraints)

            state.headerBound = if (isVertical) headerPlaceable.height.toFloat() else headerPlaceable.width.toFloat()
            state.footerBound = if (isVertical) footerPlaceable.height.toFloat() else footerPlaceable.width.toFloat()

            // ──────────────────────────────────────────────────────────────────
            // 【核心渲染引擎：基于 graphicsLayer 的零重绘位移架构】
            //
            // 架构优势：
            // 1. 静态约束测量：Content 组件在布局阶段始终保持全屏测量，不因拖拽发生约束变更，
            //    从根源上杜绝了因频繁触发 re-measurement 带来的 CPU 性能损耗与 UI 闪跳。
            // 2. 硬件加速平移：Header、Content、Footer 三层结构严格绑定指示器状态，
            //    仅在 Draw 阶段通过修改 translation 属性进行图层级别移动。
            // 3. 边界动态扩容：当且仅当发生解耦驻留时，容器通过 computed property (`addedHeight`) 
            //    动态延展物理渲染范围，确保数据增量无缝衔接，实现媲美原生底层的连贯滚动体验。
            // ──────────────────────────────────────────────────────────────────

            // 动态注入扩容尺寸，给新追加的数据提供物理渲染空间
            val contentConstraints = if (isVertical) {
                constraints.copy(
                    minHeight = constraints.maxHeight + state.addedHeight,
                    maxHeight = constraints.maxHeight + state.addedHeight
                )
            } else {
                constraints.copy(
                    minWidth = constraints.maxWidth + state.addedHeight,
                    maxWidth = constraints.maxWidth + state.addedHeight
                )
            }
            val contentPlaceable = measurables.first { it.layoutId == "content" }.measure(contentConstraints)

            layout(constraints.maxWidth, constraints.maxHeight) {

                if (isVertical) {
                    val cxHeader = (constraints.maxWidth - headerPlaceable.width) / 2
                    val cxFooter = (constraints.maxWidth - footerPlaceable.width) / 2

                    // Content 应用可能被解耦的平移量
                    contentPlaceable.placeWithLayer(0, 0) {
                        translationY = state.currentContentOffset
                    }
                    headerPlaceable.placeWithLayer(x = cxHeader, y = -headerPlaceable.height) {
                        translationY = state.indicatorOffset
                    }
                    // 永远置于真实的屏幕底部，配合 indicatorOffset 平移
                    footerPlaceable.placeWithLayer(x = cxFooter, y = constraints.maxHeight) {
                        translationY = state.indicatorOffset
                    }
                } else {
                    val cyHeader = (constraints.maxHeight - headerPlaceable.height) / 2
                    val cyFooter = (constraints.maxHeight - footerPlaceable.height) / 2

                    contentPlaceable.placeWithLayer(0, 0) {
                        translationX = state.currentContentOffset
                    }
                    headerPlaceable.placeWithLayer(x = -headerPlaceable.width, y = cyHeader) {
                        translationX = state.indicatorOffset
                    }
                    footerPlaceable.placeWithLayer(x = constraints.maxWidth, y = cyFooter) {
                        translationX = state.indicatorOffset
                    }
                }
            }

        }
    }
}





/**
 * 默认头部指示器（根据排版方向自适应宽高分配）
 *
 * @param flag 当前头部刷新状态机标记
 * @param orientation 整体容器的滚动方向
 */
@Composable
fun RefreshHeader(flag: SmartRefreshFlag, orientation: Orientation = Orientation.Vertical) {
    Box(
        modifier = Modifier.let { 
            if (orientation == Orientation.Vertical) it.fillMaxWidth().height(50.dp) 
            else it.fillMaxHeight().width(50.dp) 
        }
    ) {
        // 无限动画过渡，驱动 Loading 的持续旋转
        val refreshAnimate by rememberInfiniteTransition(label = "MyRefreshHeader").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
            label = "rotate"
        )
        Image(
            painter = painterResource(id = R.drawable.icon_refresh_loading),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .size(20.dp)
                // 仅在明确进行刷新动作时触发旋转
                .rotate(if (flag == SmartRefreshFlag.REFRESHING) refreshAnimate else 0f)
        )
    }
}

/**
 * 默认尾部指示器（承载“没有更多了”的原生跟手交互底座）
 *
 * @param flag 当前尾部加载状态机标记
 * @param noMoreData 是否已经没有更多数据
 * @param orientation 整体容器的滚动方向
 * @param noMoreText 定制版“没有更多数据”文案
 */
@Composable
fun RefreshFooter(
    flag: SmartRefreshFlag, 
    noMoreData: Boolean, 
    orientation: Orientation = Orientation.Vertical,
    noMoreText: String? = null
) {
    // 优先采用外部定制文案，否则降级采用默认兜底字符串
    val displayNoMoreText = noMoreText ?: stringResource(R.string.compose_srl_no_more)

    if (orientation == Orientation.Vertical) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 经典悬停特性：即便 noMoreData 为 true 也强制保持有效尺寸。
                // 这确保了它可以随着用户在底部的惯性滑动被完美拉出，并吸附在视野中。
                .height(50.dp)
        ) {
            if (noMoreData) {
                // 无更多数据：静态提示模式
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = displayNoMoreText,
                        fontSize = 12.sp,
                        color = colorResource(R.color.compose_srl_text_tertiary),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // 加载中：动效提示模式
                val refreshAnimate by rememberInfiniteTransition(label = "MyRefreshFooter").animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing)),
                    label = "MyRefreshFooter"
                )
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.icon_load_more_loading),
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(if (flag == SmartRefreshFlag.REFRESHING) refreshAnimate else 0f)
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            text = stringResource(R.string.compose_srl_loading), 
                            fontSize = 14.sp,
                            color = colorResource(R.color.compose_srl_text_title),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    } else {
        // 横向布局逻辑，高度撑满，主轴宽度定宽
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(50.dp)
        ) {
            if (noMoreData) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val verticalText = displayNoMoreText.map { it.toString() }.joinToString("\n")
                    Text(
                        text = verticalText,
                        fontSize = 12.sp,
                        color = colorResource(R.color.compose_srl_text_tertiary),
                        lineHeight = 16.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val refreshAnimate by rememberInfiniteTransition(label = "MyRefreshFooter").animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing)),
                    label = "MyRefreshFooter"
                )
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.icon_load_more_loading),
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(if (flag == SmartRefreshFlag.REFRESHING) refreshAnimate else 0f)
                    )
                    val verticalLoadingText = stringResource(R.string.compose_srl_loading).map { it.toString() }.joinToString("\n")
                    Text(
                        text = verticalLoadingText, 
                        fontSize = 14.sp,
                        color = colorResource(R.color.compose_srl_text_title),
                        modifier = Modifier.padding(top = 8.dp),
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
