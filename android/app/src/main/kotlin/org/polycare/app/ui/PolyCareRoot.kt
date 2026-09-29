package org.polycare.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import org.polycare.app.ask.AskScreen
import org.polycare.app.device.DeviceCheckScreen
import org.polycare.app.home.HomeScreen
import org.polycare.app.knowledge.MemoryScreen
import org.polycare.app.knowledge.SearchScreen
import org.polycare.app.households.HouseholdsScreen
import org.polycare.app.ocr.ScanScreen
import org.polycare.app.triage.TriageScreen
import org.polycare.app.ui.components.BrandBackground
import org.polycare.app.ui.components.Wordmark
import org.polycare.app.ui.theme.Brand

private enum class Tab(val route: String, val label: String, val icon: ImageVector, val accent: Color) {
    Home("home", "Home", Icons.Outlined.Home, Brand.Plum),
    Ask("ask", "Ask", Icons.Outlined.Mic, Brand.Plum),
    Triage("triage", "Triage", Icons.Outlined.MonitorHeart, Brand.Rose),
    Search("search", "Search", Icons.Outlined.Search, Brand.Magenta),
}

private const val SEARCH_ROUTE = "search"
private const val ASK_ROUTE = "ask"
private const val TRIAGE_ROUTE = "triage"
private const val MEMORY_ROUTE = "memory"
private const val SCAN_ROUTE = "scan"
private const val SYSTEM_ROUTE = "system"
private const val HOUSEHOLDS_ROUTE = "households"
private const val DUE_LIST_ROUTE = "due-list"

private data class DrawerItem(val route: String, val label: String, val icon: ImageVector, val accent: Color, val milestone: String? = null)

/** Every real screen, plus a few not-yet-built ones shown disabled with their milestone — the
 * same "arrives in M3" honesty Home's tiles already use, not hidden and not faked. */
private val DrawerDestinations = listOf(
    DrawerItem(Tab.Home.route, "Home", Tab.Home.icon, Tab.Home.accent),
    DrawerItem(Tab.Ask.route, "Ask", Tab.Ask.icon, Tab.Ask.accent),
    DrawerItem(Tab.Triage.route, "Triage", Tab.Triage.icon, Tab.Triage.accent),
    DrawerItem(Tab.Search.route, "Search", Tab.Search.icon, Tab.Search.accent),
    DrawerItem(MEMORY_ROUTE, "Memory Inspector", Icons.Outlined.Psychology, Brand.PlumDeep),
    DrawerItem(SCAN_ROUTE, "Scan", Icons.Outlined.DocumentScanner, Brand.Positive),
    DrawerItem(HOUSEHOLDS_ROUTE, "Households", Icons.Outlined.Groups, Brand.Pink),
    DrawerItem(DUE_LIST_ROUTE, "Due list", Icons.Outlined.CalendarMonth, Brand.Red),
    DrawerItem(SYSTEM_ROUTE, "System", Icons.Outlined.Tune, Brand.InkMuted),
)

private val DrawerComingLater = listOf(
    DrawerItem("sync", "Sync", Icons.Outlined.Sync, Brand.InkMuted, milestone = "M6"),
)

@Composable
fun PolyCareRoot(
    autoBenchPoints: Int? = null,
    debugSearch: String? = null,
    debugAsk: String? = null,
    debugTriage: Boolean = false,
    debugRoute: String? = null,
    debugOcrImagePath: String? = null,
    debugOpenDrawer: Boolean = false,
) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.hierarchy?.firstOrNull()?.route
    val drawerState = rememberDrawerState(if (debugOpenDrawer) DrawerValue.Open else DrawerValue.Closed)

    fun notReady(feature: String, milestone: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar("$feature arrives in milestone $milestone")
        }
    }

    fun go(route: String) {
        scope.launch { drawerState.close() }
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            NavDrawer(currentRoute = currentRoute, onSelect = ::go, onNotReady = ::notReady)
        },
    ) {
        BrandBackground {
            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = {
                    SnackbarHost(snackbar) {
                        Snackbar(it, containerColor = Brand.PlumDeep, contentColor = Brand.Paper, shape = MaterialTheme.shapes.medium)
                    }
                },
                bottomBar = {
                    FloatingTabBar(currentRoute) { tab -> go(tab.route) }
                },
            ) { padding ->
                // +16dp beyond Scaffold's own measurement: the floating pill fades its top edge
                // into transparency by design, but its opaque lower two-thirds still needs real
                // scroll clearance, or the last row of a long list (e.g. Home's tool grid) ends
                // up sitting behind it instead of above it.
                val content = PaddingValues(bottom = padding.calculateBottomPadding() + 40.dp)
                val start = when {
                    autoBenchPoints != null -> SYSTEM_ROUTE
                    debugSearch != null -> SEARCH_ROUTE
                    debugAsk != null -> ASK_ROUTE
                    debugTriage -> TRIAGE_ROUTE
                    debugOcrImagePath != null -> SCAN_ROUTE
                    debugRoute != null -> debugRoute
                    else -> Tab.Home.route
                }
                NavHost(nav, startDestination = start) {
                    composable(Tab.Home.route) {
                        HomeScreen(
                            contentPadding = content,
                            onAsk = { voice -> go(if (voice) "$ASK_ROUTE?voice=true" else ASK_ROUTE) },
                            onNavigate = ::go,
                            onNotReady = ::notReady,
                            onMenu = { scope.launch { drawerState.open() } },
                        )
                    }
                    composable(SYSTEM_ROUTE) {
                        DeviceCheckScreen(contentPadding = content, autoBenchPoints = autoBenchPoints, onMenu = { scope.launch { drawerState.open() } })
                    }
                    composable(SEARCH_ROUTE) { SearchScreen(contentPadding = content, onBack = { nav.popBackStack() }, initialQuery = debugSearch) }
                    composable(
                        "$ASK_ROUTE?voice={voice}",
                        arguments = listOf(navArgument("voice") { type = NavType.BoolType; defaultValue = false }),
                    ) { entry ->
                        AskScreen(
                            contentPadding = content,
                            onBack = { nav.popBackStack() },
                            initialQuery = debugAsk,
                            autoStartVoice = entry.arguments?.getBoolean("voice") ?: false,
                        )
                    }
                    composable(TRIAGE_ROUTE) { TriageScreen(contentPadding = content, onBack = { nav.popBackStack() }) }
                    composable(MEMORY_ROUTE) { MemoryScreen(contentPadding = content, onBack = { nav.popBackStack() }) }
                    composable(SCAN_ROUTE) { ScanScreen(contentPadding = content, onBack = { nav.popBackStack() }, debugImagePath = debugOcrImagePath) }
                    composable(HOUSEHOLDS_ROUTE) { HouseholdsScreen(contentPadding = content, onBack = { nav.popBackStack() }) }
                    composable(DUE_LIST_ROUTE) { org.polycare.app.duelist.DueListScreen(contentPadding = content, onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}

@Composable
private fun NavDrawer(currentRoute: String?, onSelect: (String) -> Unit, onNotReady: (String, String) -> Unit) {
    ModalDrawerSheet(drawerContainerColor = Brand.Paper) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 24.dp)) {
            Wordmark(logoSize = 24.dp)
            Spacer(Modifier.height(28.dp))
            DrawerDestinations.forEach { item ->
                NavigationDrawerItem(
                    label = { Text(item.label, style = MaterialTheme.typography.titleMedium) },
                    icon = { Icon(item.icon, contentDescription = null, tint = item.accent) },
                    selected = currentRoute == item.route,
                    onClick = { onSelect(item.route) },
                    shape = MaterialTheme.shapes.large,
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = item.accent.copy(alpha = 0.14f),
                        selectedTextColor = Brand.Ink,
                        selectedIconColor = item.accent,
                        unselectedTextColor = Brand.Ink,
                        unselectedIconColor = Brand.InkMuted,
                    ),
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text("COMING LATER", style = MaterialTheme.typography.labelMedium, color = Brand.InkMuted, modifier = Modifier.padding(start = 12.dp))
            Spacer(Modifier.height(6.dp))
            DrawerComingLater.forEach { item ->
                NavigationDrawerItem(
                    label = { Text(item.label, style = MaterialTheme.typography.titleMedium, color = Brand.InkMuted) },
                    icon = { Icon(item.icon, contentDescription = null, tint = Brand.InkMuted) },
                    badge = { Text(item.milestone.orEmpty(), style = MaterialTheme.typography.labelSmall, color = Brand.InkMuted) },
                    selected = false,
                    onClick = { onNotReady(item.label, item.milestone.orEmpty()) },
                    shape = MaterialTheme.shapes.large,
                    colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Brand.InkMuted, unselectedIconColor = Brand.InkMuted),
                    modifier = Modifier.padding(vertical = 3.dp),
                )
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
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Tab.entries.forEach { tab ->
                val selected = currentRoute == tab.route
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(if (selected) tab.accent else Color.Transparent)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            onSelect(tab)
                        }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
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
