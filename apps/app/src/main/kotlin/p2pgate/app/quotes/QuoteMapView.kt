package p2pgate.app.quotes

import android.preference.PreferenceManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import p2pgate.app.BuildConfig
import p2pgate.app.R

/** Camera padding (px) when fitting the seller pins. */
private const val FIT_PADDING_PX = 96
private const val SINGLE_MARKER_ZOOM = 13.0

/**
 * Seller-location map for the quote screen (osmdroid + OSM MAPNIK tiles — no
 * Google Play Services, no API key). Tapping a pin runs the same Choose flow
 * as the list view. Deliberately no ACCESS_FINE_LOCATION: the map shows the
 * sellers, it never shares the buyer's position.
 */
@Composable
fun QuoteMapView(
    markers: List<QuoteMarker>,
    onMarkerChoose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnChoose by rememberUpdatedState(onMarkerChoose)

    val mapView = remember {
        // osmdroid setup per its docs: load the shared-prefs configuration,
        // set the user agent the OSM tile-usage policy requires, keep the
        // built-in disk tile cache enabled (the default policy).
        Configuration.getInstance()
            .load(context, PreferenceManager.getDefaultSharedPreferences(context))
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(3.0)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // Center only when the marker set changes — never fight the user's pan.
    // Runs as a delayed LaunchedEffect: osmdroid's own init/layout pass resets
    // the camera if we center before the view has settled, so we wait it out.
    val markersKey = markers.joinToString("|") { m -> "${m.quoteId}@${m.lat},${m.lon}" }
    LaunchedEffect(markersKey) {
        if (markers.isEmpty()) return@LaunchedEffect
        // osmdroid's own init/layout pass can reset the camera after our first
        // setCenter — so we center twice: once shortly after composition and
        // once more after the view has fully settled.
        val centerNow = {
            when {
                markers.size > 1 -> mapView.zoomToBoundingBox(
                    BoundingBox.fromGeoPoints(markers.map { GeoPoint(it.lat, it.lon) }),
                    false,
                    FIT_PADDING_PX,
                )
                markers.size == 1 -> {
                    mapView.controller.setCenter(GeoPoint(markers[0].lat, markers[0].lon))
                    mapView.controller.setZoom(SINGLE_MARKER_ZOOM)
                }
            }
            mapView.invalidate()
        }
        kotlinx.coroutines.delay(400)
        mapView.post { centerNow() }
        kotlinx.coroutines.delay(600)
        mapView.post { centerNow() }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            map.overlays.removeAll { it is Marker }
            markers.forEach { m ->
                val marker = Marker(map)
                marker.position = GeoPoint(m.lat, m.lon)
                marker.title = context.getString(
                    R.string.quote_marker_title,
                    m.minAmount,
                    m.maxAmount,
                    m.etaMinutes,
                )
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                marker.setOnMarkerClickListener { _, _ ->
                    currentOnChoose(m.quoteId)
                    true
                }
                map.overlays.add(marker)
            }
            map.invalidate()
        },
    )
}
