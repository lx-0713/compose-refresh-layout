# Compose SmartRefresh

[![Maven Central](https://img.shields.io/maven-central/v/io.github.lx-0713/compose-smart-refresh.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.lx-0713/compose-smart-refresh)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

基于 **Jetpack Compose** 构建的声明式嵌套滑动刷新容器。提供极简、高性能的下拉刷新与上拉加载能力。其核心使用原生 `graphicsLayer` 进行零重绘渲染，具备丝滑的物理阻尼回弹效果。

---

## 特性

| 特性 | 说明 |
|------|------|
| **零重排渲染** | 放弃传统约束改变（Constraints），采用纯粹的 `graphicsLayer` 图层硬件加速平移，手势跟手阶段 CPU 零卡顿。 |
| **多向排版支持** | 通过传入 `Orientation`，原生无缝兼容**垂直方向**（`LazyColumn`）与**横向方向**（`LazyRow` / `HorizontalPager`）。 |
| **解耦数据驻留** | 在异步加载更多数据时，提供**视口解耦引擎**，确保滚动坐标系不发生跳跃，体验媲美原生平台组件。 |
| **智能预留空间** | 提前测量首尾 Header 与 Footer 节点边界，结合物理动态扩容，彻底消除首帧闪动问题。 |
| **极致状态机** | 基于 `derivedStateOf` 的计算属性切断高频测量渗透，内部生命周期（如 `PULLING`, `REFRESHING` 等）分发严密。 |

---

## 环境要求

- **Kotlin** 1.9+
- **Jetpack Compose** 1.5.0+ 

---

## 安装

在模块的 `build.gradle.kts` 或 `build.gradle` 中添加依赖：

```kotlin
dependencies {
    implementation("io.github.lx-0713:compose-smart-refresh:1.0.0")
}
```

> **确保项目的 `settings.gradle.kts` 或根 `build.gradle.kts` 中包含 `mavenCentral()` 仓库：**
> ```kotlin
> repositories {
>     mavenCentral()
> }
> ```

---

## 快速开始

### 基础用法

通过包裹任意支持嵌套滚动的组件（如 `LazyColumn`）即可赋予其双向手势刷新能力。使用 `SmartSwipeRefreshState` 追踪刷新或加载的完成态。

```kotlin
import com.lx.compose.refresh.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun SmartRefreshScreen() {
    val scope = rememberCoroutineScope()
    // 1. 初始化核心状态机
    val state = rememberSmartSwipeRefreshState()
    var items by remember { mutableStateOf((1..20).toList()) }

    // 2. 将容器包裹于目标列表外层
    SmartSwipeRefresh(
        state = state,
        onRefresh = {
            scope.launch {
                delay(1500) // 模拟网络请求
                items = (1..20).toList()
                state.finishRefresh() // 3. 必须显式调用结束
            }
        },
        onLoadMore = {
            scope.launch {
                delay(1500) // 模拟网络请求
                if (items.size > 50) {
                    state.finishLoadMore(noMoreData = true) // 4. 标识已无更多数据
                } else {
                    items = items + (items.size + 1..items.size + 10)
                    state.finishLoadMore()
                }
            }
        }
    ) {
        // 支持 NestedScroll 的任意子组件
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items) { item ->
                Text(
                    text = "列表项 $item",
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
```

---

## API 速查

### SmartSwipeRefresh (核心容器)

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `state` | 必须，核心状态机引擎（`SmartSwipeRefreshState`） | 无 |
| `orientation` | 布局方向，`Orientation.Vertical` 或 `Horizontal` | `Vertical` |
| `onRefresh` | 触发下拉刷新的回调（向右拉同理） | `null` |
| `onLoadMore` | 触发上拉加载的回调（向左拉同理） | `null` |
| `header` | 自定义头部 Composable，入参需自行接收状态机数据 | `RefreshHeader` |
| `footer` | 自定义尾部 Composable，入参需自行接收状态机数据 | `RefreshFooter` |
| `noMoreDataText`| 若使用默认 Footer，可自定义底部边界文案 | `null` (自动取 string 资源) |
| `content` | 可滚动的核心列表组件 | 无 |

### SmartSwipeRefreshState (状态机)

使用 `rememberSmartSwipeRefreshState()` 快速获取。支持手动操控生命周期：

| 核心方法/属性 | 说明 |
|--------------|------|
| `finishRefresh()` | 结束下拉刷新（若不在 `REFRESHING` 态则无操作） |
| `finishLoadMore(noMoreData: Boolean = false)` | 结束加载。如果传递 `true` 将解除底部的回弹阻尼，提供无数据的驻留特效 |
| `resetNoMoreData()` | 重置无更多数据的状态（如搜索切换条件后） |
| `stickinessLevel: Float` | 动态阻尼系数（0.1~1f），值越小拉动越紧致 |
| `enableRefresh: Boolean` | 动态开关下拉刷新功能 |
| `enableLoadMore: Boolean`| 动态开关上拉加载功能 |

---

## 许可证

[Apache-2.0](https://opensource.org/licenses/Apache-2.0)
