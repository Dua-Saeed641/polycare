package org.polycare.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import org.polycare.app.device.DeviceCheckScreen
import org.polycare.app.home.HomeScreen
import org.polycare.app.ui.components.BrandBackground
import org.polycare.app.ui.theme.Brand

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Outlined.Home),
    System("system", "System", Icons.Outlined.Tune),
}

@Composable
fun PolyCareRoot() {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.hierarchy?.firstOrNull()?.route

    BrandBackground {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            snackbarHost = {
                SnackbarHost(snackbar) {
                    Snackbar(it, containerColor = Brand.PlumDeep, contentColor = Brand.Paper, shape = MaterialTheme.shapes.medium)
                }
            },
            bottomBar = {
                FloatingTabBar(currentRoute) { tab ->
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
        ) { padding ->
            val content = PaddingValues(bottom = padding.calculateBottomPadding())
            NavHost(nav, startDestination = Tab.Home.route) {
                composable(Tab.Home.route) {
                    HomeScreen(
                        contentPadding = content,
                        onNotReady = { feature, milestone ->
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar("$feature arrives in milestone $milestone")
                            }
                        },
                    )
                }
                composable(Tab.System.route) { DeviceCheckScreen(contentPadding = content) }
            }
        }
    }
}

@Composable
private fun FloatingTabBar(currentRoute: String?, onSelect: (Tab) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Brand.Paper.copy(alpha = 0f),
                    0.45f to Brand.Paper.copy(alpha = 0.85f),
                    1f to Brand.Paper,
                ),
            )
            .navigationBarsPadding()
            .padding(top = 28.dp, bottom = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .shadow(elevation = 18.dp, shape = CircleShape, ambientColor = Brand.Plum, spotColor = Brand.Plum)
                .clip(CircleShape)
                .background(Brand.White)
                .border(1.dp, Brand.Line, CircleShape)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Tab.entries.forEach { tab ->
                val selected = currentRoute == tab.route
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(if (selected) Brand.Plum else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            onSelect(tab)
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = if (selected) Brand.Paper else Brand.InkMuted
                    Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(20.dp))
                    if (selected) {
                        Box(Modifier.width(8.dp))
                        Text(tab.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = tint)
                    }
                }
            }
        }
    }
}
