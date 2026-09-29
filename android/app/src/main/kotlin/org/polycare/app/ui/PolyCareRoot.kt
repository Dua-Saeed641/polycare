package org.polycare.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material.icons.outlined.CallMerge
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.HorizontalDivider
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
private const val SYNC_ROUTE = "sync"
private const val RADAR_ROUTE = "radar"
private const val CONFLICTS_ROUTE = "conflicts"
private const val MEDICINE_ROUTE = "medicine"
private const val TIPS_ROUTE = "tips"

private data class DrawerItem(val route: String, val label: String, val icon: ImageVector, val accent: Color)

/** Every screen. Nothing is listed that is not built, and nothing built is hidden. */
private val DrawerDestinations = listOf(
    DrawerItem(Tab.Home.route, "Home", Tab.Home.icon, Tab.Home.accent),
    DrawerItem(Tab.Ask.route, "Ask", Tab.Ask.icon, Tab.Ask.accent),
    DrawerItem(Tab.Triage.route, "Triage", Tab.Triage.icon, Tab.Triage.accent),
    DrawerItem(Tab.Search.route, "Search", Tab.Search.icon, Tab.Search.accent),
    DrawerItem(MEDICINE_ROUTE, "Medicines & counselling", Icons.Outlined.Medication, Brand.Positive),
    DrawerItem(TIPS_ROUTE, "Team tips", Icons.Outlined.Lightbulb, Brand.Positive),
    DrawerItem(HOUSEHOLDS_ROUTE, "Households", Icons.Outlined.Groups, Brand.Pink),
    DrawerItem(DUE_LIST_ROUTE, "Due list", Icons.Outlined.CalendarMonth, Brand.Red),
    DrawerItem(SCAN_ROUTE, "Scan", Icons.Outlined.DocumentScanner, Brand.Positive),
    DrawerItem(RADAR_ROUTE, "Outbreak Radar", Icons.Outlined.Radar, Brand.Rose),
    DrawerItem(CONFLICTS_ROUTE, "Conflict inbox", Icons.Outlined.CallMerge, Brand.Magenta),
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
                    FloatingTabBar(currentRoute) { tab -> go(tab.route) }
                },
            ) { padding ->
                // +16dp beyond Scaffold's own measurement: the floating pill fades its top edge
                // into transparency by design, but its opaque lower two-thirds still needs real
                // scroll clearance, or the last row of a long list (e.g. Home's tool grid) ends
                // up sitting behind it instead of above it.
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
                            onMenu = { scope.launch { drawerState.open() } },
                        )
                    }
                    composable(SYSTEM_ROUTE) {
                        DeviceCheckScreen(contentPadding = content, autoBenchPoints = autoBenchPoints, onMenu = { scope.launch { drawerState.open() } })
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

/**
 * Bottom navigation: four equal-width destinations, every one always labelled (an icon alone
 * says nothing to a first-time user or a screen reader), at least 64dp tall, announced as tabs
 * with a selected state. Solid rather than floating, so nothing scrolls behind it.
 */
@Composable
private fun FloatingTabBar(currentRoute: String?, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Brand.White)) {
        HorizontalDivider(color = Brand.Line)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Tab.entries.forEach { tab ->
                TabItem(tab, currentRoute == tab.route) { onSelect(tab) }
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .heightIn(min = 64.dp)
            .clip(MaterialTheme.shapes.large)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .clip(CircleShape)
                .background(if (selected) tab.accent.copy(alpha = 0.16f) else Color.Transparent)
                .padding(horizontal = 22.dp, vertical = 4.dp),
        ) {
            Icon(tab.icon, contentDescription = null, tint = if (selected) Brand.Ink else Brand.InkMuted, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            tab.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) Brand.Ink else Brand.InkMuted,
        )
    }
}
