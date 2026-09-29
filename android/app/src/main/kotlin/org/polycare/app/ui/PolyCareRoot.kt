package org.polycare.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.foundation.layout.heightIn
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
import org.polycare.app.sync.SyncScreen
import org.polycare.app.radar.RadarScreen
import org.polycare.app.conflicts.ConflictInboxScreen
import org.polycare.app.medicine.MedicineScreen
import org.polycare.app.team.TeamTipsScreen
import org.polycare.app.ui.MoreScreen
import org.polycare.app.ui.components.BrandBackground
import org.polycare.app.ui.components.Wordmark
import org.polycare.app.ui.theme.Brand

private enum class Tab(val route: String, val label: String, val icon: ImageVector, val accent: Color) {
    Home("home", "Today", Icons.Outlined.Home, Brand.Plum),
    Ask("ask", "Ask", Icons.Outlined.Mic, Brand.Plum),
    Triage("triage", "Triage", Icons.Outlined.MonitorHeart, Brand.Rose),
    Families("households", "Families", Icons.Outlined.Groups, Brand.Pink),
    More("more", "More", Icons.Outlined.Apps, Brand.PlumDeep),
}

private const val SEARCH_ROUTE = "search"
private const val ASK_ROUTE = "ask"
private const val TRIAGE_ROUTE = "triage"
private const val MEMORY_ROUTE = "memory"
private const val SCAN_ROUTE = "scan"
private const val SYSTEM_ROUTE = "system"
private const val HOUSEHOLDS_ROUTE = "households"
private const val DUE_LIST_ROUTE = "due-list"
private const val SYNC_ROUTE = "sync"
private const val RADAR_ROUTE = "radar"
private const val CONFLICTS_ROUTE = "conflicts"
private const val MEDICINE_ROUTE = "medicine"
private const val TIPS_ROUTE = "tips"
private const val MORE_ROUTE = "more"

private data class DrawerItem(val route: String, val label: String, val icon: ImageVector, val accent: Color)

/** Every screen. Nothing is listed that is not built, and nothing built is hidden. */
private val DrawerDestinations = listOf(
    DrawerItem(Tab.Home.route, "Home", Tab.Home.icon, Tab.Home.accent),
    DrawerItem(Tab.Ask.route, "Ask", Tab.Ask.icon, Tab.Ask.accent),
    DrawerItem(Tab.Triage.route, "Triage", Tab.Triage.icon, Tab.Triage.accent),
    DrawerItem(SEARCH_ROUTE, "Search", Icons.Outlined.Search, Brand.Magenta),
    DrawerItem(MORE_ROUTE, "All tools", Tab.More.icon, Tab.More.accent),
    DrawerItem(MEDICINE_ROUTE, "Medicines & counselling", Icons.Outlined.Medication, Brand.Positive),
    DrawerItem(TIPS_ROUTE, "Team tips", Icons.Outlined.Lightbulb, Brand.Positive),
    DrawerItem(HOUSEHOLDS_ROUTE, "Households", Icons.Outlined.Groups, Brand.Pink),
    DrawerItem(DUE_LIST_ROUTE, "Due list", Icons.Outlined.CalendarMonth, Brand.Red),
    DrawerItem(SCAN_ROUTE, "Scan", Icons.Outlined.DocumentScanner, Brand.Positive),
    DrawerItem(RADAR_ROUTE, "Outbreak Radar", Icons.Outlined.Radar, Brand.Rose),
    DrawerItem(CONFLICTS_ROUTE, "Conflict inbox", Icons.AutoMirrored.Outlined.CallMerge, Brand.Magenta),
    DrawerItem(SYNC_ROUTE, "Sync", Icons.Outlined.CloudSync, Brand.Plum),
    DrawerItem(MEMORY_ROUTE, "Memory Inspector", Icons.Outlined.Psychology, Brand.PlumDeep),
    DrawerItem(SYSTEM_ROUTE, "System", Icons.Outlined.Tune, Brand.InkMuted),
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
    val currentRoute = backStack?.destination?.hierarchy?.firstOrNull()?.route?.substringBefore('?')
    val selectedTab = when (currentRoute) {
        Tab.Home.route -> Tab.Home
        Tab.Ask.route -> Tab.Ask
        Tab.Triage.route -> Tab.Triage
        Tab.Families.route -> Tab.Families
        else -> Tab.More
    }
    val drawerState = rememberDrawerState(if (debugOpenDrawer) DrawerValue.Open else DrawerValue.Closed)

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
            NavDrawer(currentRoute = currentRoute, onSelect = ::go)
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
                    PrimaryTabBar(selectedTab) { tab -> go(tab.route) }
                },
            ) { padding ->
                // Keep the last action clear of persistent navigation on short screens and
                // when the user increases system text size.
                val content = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp)
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
                            onMenu = { go(MORE_ROUTE) },
                        )
                    }
                    composable(SYSTEM_ROUTE) {
                        DeviceCheckScreen(contentPadding = content, autoBenchPoints = autoBenchPoints, onMenu = { go(MORE_ROUTE) })
                    }
                    composable(SEARCH_ROUTE) { SearchScreen(contentPadding = content, onBack = { nav.popBackStack() }, initialQuery = debugSearch) }
                    composable(
                        "$ASK_ROUTE?voice={voice}&q={q}",
                        arguments = listOf(
                            navArgument("voice") { type = NavType.BoolType; defaultValue = false },
                            navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null },
                        ),
                    ) { entry ->
                        AskScreen(
                            contentPadding = content,
                            onBack = { nav.popBackStack() },
                            initialQuery = entry.arguments?.getString("q") ?: debugAsk,
                            autoStartVoice = entry.arguments?.getBoolean("voice") ?: false,
                        )
                    }
                    composable(TRIAGE_ROUTE) { TriageScreen(contentPadding = content, onBack = { nav.popBackStack() }) }
                    composable(MEMORY_ROUTE) { MemoryScreen(contentPadding = content, onBack = { nav.popBackStack() }) }
                    composable(SCAN_ROUTE) { ScanScreen(contentPadding = content, onBack = { nav.popBackStack() }, debugImagePath = debugOcrImagePath) }
                    composable(HOUSEHOLDS_ROUTE) { HouseholdsScreen(contentPadding = content, onBack = { nav.popBackStack() }) }
                    composable(DUE_LIST_ROUTE) { org.polycare.app.duelist.DueListScreen(contentPadding = content, onBack = { nav.popBackStack() }) }
                    composable(TIPS_ROUTE) { TeamTipsScreen(contentPadding = content, onBack = { nav.popBackStack() }) }
                    composable(SYNC_ROUTE) { SyncScreen(contentPadding = content, onBack = { nav.popBackStack() }) }
                    composable(RADAR_ROUTE) { RadarScreen(contentPadding = content, onBack = { nav.popBackStack() }) }
                    composable(CONFLICTS_ROUTE) { ConflictInboxScreen(contentPadding = content, onBack = { nav.popBackStack() }) }
                    composable(MEDICINE_ROUTE) {
                        MedicineScreen(
                            contentPadding = content,
                            onBack = { nav.popBackStack() },
                            onAsk = { q -> go("$ASK_ROUTE?q=${android.net.Uri.encode(q)}") },
                        )
                    }
                    composable(MORE_ROUTE) { MoreScreen(contentPadding = content, onNavigate = ::go) }
                }
            }
        }
    }
}

@Composable
private fun NavDrawer(currentRoute: String?, onSelect: (String) -> Unit) {
    ModalDrawerSheet(drawerContainerColor = Brand.Paper) {
        Column(
            Modifier
                .statusBarsPadding()
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Wordmark(logoSize = 24.dp)
            Spacer(Modifier.height(24.dp))
            DrawerDestinations.forEach { item ->
                NavigationDrawerItem(
                    label = { Text(item.label, style = MaterialTheme.typography.titleMedium) },
                    icon = { Icon(item.icon, contentDescription = null, tint = if (currentRoute == item.route) Brand.Ink else Brand.InkMuted) },
                    selected = currentRoute == item.route,
                    onClick = { onSelect(item.route) },
                    shape = MaterialTheme.shapes.large,
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = item.accent.copy(alpha = 0.16f),
                        selectedTextColor = Brand.Ink,
                        selectedIconColor = Brand.Ink,
                        unselectedTextColor = Brand.Ink,
                        unselectedIconColor = Brand.InkMuted,
                    ),
                    modifier = Modifier.padding(vertical = 2.dp).heightIn(min = 52.dp),
                )
            }
        }
    }
}

/** Five stable daily destinations, with labels and selected state always visible. */
@Composable
private fun PrimaryTabBar(selectedTab: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(
        containerColor = Brand.White,
        tonalElevation = 3.dp,
    ) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selectedTab == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label, maxLines = 1) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = tab.accent,
                    selectedTextColor = Brand.Ink,
                    indicatorColor = tab.accent.copy(alpha = 0.14f),
                    unselectedIconColor = Brand.InkMuted,
                    unselectedTextColor = Brand.InkMuted,
                ),
            )
        }
    }
}
