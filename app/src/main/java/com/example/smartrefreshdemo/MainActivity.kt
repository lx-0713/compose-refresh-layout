package com.example.smartrefreshdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lx.compose.refresh.SmartSwipeRefresh
import com.lx.compose.refresh.rememberSmartSwipeRefreshState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DemoScreen()
                }
            }
        }
    }
}

@Composable
fun DemoScreen() {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Vertical", "Horizontal", "Grid")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTabIndex) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = { Text(title) }
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTabIndex) {
                0 -> VerticalDemo()
                1 -> HorizontalDemo()
                2 -> GridDemo()
            }
        }
    }
}

@Composable
fun VerticalDemo() {
    val state = rememberSmartSwipeRefreshState()
    val scope = rememberCoroutineScope()
    var itemCount by remember { mutableIntStateOf(20) }

    SmartSwipeRefresh(
        state = state,
        orientation = Orientation.Vertical,
        noMoreDataText = "— 我可是有底线的 (垂直) —",
        onRefresh = {
            scope.launch {
                delay(1000)
                itemCount = 20
                state.finishRefresh()
                state.finishLoadMore(noMoreData = false)
            }
        },
        onLoadMore = {
            scope.launch {
                delay(1000)
                if (itemCount >= 60) {
                    state.finishLoadMore(noMoreData = true)
                } else {
                    itemCount += 20
                    state.finishLoadMore(noMoreData = false)
                }
            }
        }
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(itemCount) { index ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .height(80.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Vertical Item $index")
                    }
                }
            }
        }
    }
}

@Composable
fun HorizontalDemo() {
    val state = rememberSmartSwipeRefreshState()
    val scope = rememberCoroutineScope()
    var itemCount by remember { mutableIntStateOf(10) }

    SmartSwipeRefresh(
        state = state,
        orientation = Orientation.Horizontal,
        noMoreDataText = "没有更多了",
        onRefresh = {
            scope.launch {
                delay(1000)
                itemCount = 10
                state.finishRefresh()
                state.finishLoadMore(noMoreData = false)
            }
        },
        onLoadMore = {
            scope.launch {
                delay(1000)
                if (itemCount >= 30) {
                    state.finishLoadMore(noMoreData = true)
                } else {
                    itemCount += 10
                    state.finishLoadMore(noMoreData = false)
                }
            }
        }
    ) {
        LazyRow(modifier = Modifier.fillMaxSize()) {
            items(itemCount) { index ->
                Card(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 8.dp, vertical = 16.dp)
                        .width(120.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Horiz Item $index")
                    }
                }
            }
        }
    }
}

@Composable
fun GridDemo() {
    val state = rememberSmartSwipeRefreshState()
    val scope = rememberCoroutineScope()
    var itemCount by remember { mutableIntStateOf(30) }

    SmartSwipeRefresh(
        state = state,
        orientation = Orientation.Vertical,
        onRefresh = {
            scope.launch {
                delay(1000)
                itemCount = 30
                state.finishRefresh()
                state.finishLoadMore(noMoreData = false)
            }
        },
        onLoadMore = {
            scope.launch {
                delay(1000)
                if (itemCount >= 90) {
                    state.finishLoadMore(noMoreData = true)
                } else {
                    itemCount += 30
                    state.finishLoadMore(noMoreData = false)
                }
            }
        }
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize()
        ) {
            items(itemCount) { index ->
                Card(
                    modifier = Modifier
                        .padding(8.dp)
                        .height(100.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Grid $index")
                    }
                }
            }
        }
    }
}
