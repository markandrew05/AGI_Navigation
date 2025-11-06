package com.example.aginavigation.ui.routes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.aginavigation.R
import com.example.aginavigation.data.RouteRenderConfig
import com.example.aginavigation.ui.util.RouteArrowDrawer
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import androidx.core.graphics.toColorInt
import kotlin.math.max

class RouteDetailFragment : Fragment(), OnMapReadyCallback {

    private var googleMap: GoogleMap? = null
    private var routePoints: ArrayList<LatLng>? = null
    private val routeArrowDrawer = RouteArrowDrawer()
    private var lastArrowZoomBucket: Int? = null

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 2001
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_route_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnBack: ImageButton = view.findViewById(R.id.btnBack)
        btnBack.setOnClickListener { findNavController().navigateUp() }

        val btnStart: Button = view.findViewById(R.id.btnStartNavigation)
        btnStart.setOnClickListener {
            // Placeholder - start navigation later
            findNavController().navigateUp()
        }

        // Retrieve route points if provided
        @Suppress("DEPRECATION")
        routePoints = arguments?.getParcelableArrayList("route_points")

        val btnViewFull: Button? = view.findViewById(R.id.btnViewFullMap)
        btnViewFull?.setOnClickListener {
            // Open the fullscreen MapFragment and pass the same points
            val bundle = Bundle().apply { putParcelableArrayList("route_points", routePoints) }
            findNavController().navigate(R.id.navigation_map, bundle)
        }

        // Retrieve and display route name, summary and fare
        val routeName = arguments?.getString("destinationName")
        val routeSummary = arguments?.getString("routeSummary")
        val routeFare = arguments?.getString("routeFare")

        // Update the UI with route information
        view.findViewById<TextView>(R.id.tvRouteName)?.text = routeName ?: "Route Details"
        view.findViewById<TextView>(R.id.tvSummary)?.text = routeSummary ?: "No description available"
        view.findViewById<TextView>(R.id.tvFareValue)?.text = routeFare ?: "N/A"

        // Retrieve and display route detail info if available
        @Suppress("DEPRECATION")
        val routeInfo = arguments?.getSerializable("route_detail_info") as? RouteDetailInfo
        if (routeInfo != null) {
            populateRouteDetails(view, routeInfo)
        }

        // Insert a SupportMapFragment into the map_preview container
        val existing = childFragmentManager.findFragmentById(R.id.map_preview)
        if (existing == null) {
            val mapFragment = SupportMapFragment.newInstance()
            childFragmentManager.beginTransaction()
                .replace(R.id.map_preview, mapFragment)
                .commitNowAllowingStateLoss()
            mapFragment.getMapAsync(this)
        } else if (existing is SupportMapFragment) {
            (existing as SupportMapFragment).getMapAsync(this)
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        routePoints?.let { points ->
            if (points.isNotEmpty()) {
                drawRoute(points)
                // show user location marker if permission available; do not recenter when a route is shown
                showUserLocationIfPermitted(map, recenter = false)
                return
            }
        }

        // default behavior if no points provided
        val default = LatLng(13.1362, 123.7380)
        googleMap?.addMarker(com.google.android.gms.maps.model.MarkerOptions().position(default).title("Marker"))
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(default, 14f))

        // If no route, center on user when available
        showUserLocationIfPermitted(map, recenter = true)
    }

    // Show 'You are here' marker and optionally recenter camera if recenter == true
    @SuppressLint("MissingPermission")
    private fun showUserLocationIfPermitted(map: GoogleMap, recenter: Boolean) {
        val fineLocationGranted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineLocationGranted) {
            try {
                map.isMyLocationEnabled = true
            } catch (e: SecurityException) {
                // ignore - permission was checked
            }

            // Use LocationManager to fetch last-known location
            val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val last = try {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (e: SecurityException) {
                null
            }

            if (last != null) {
                val userLatLng = LatLng(last.latitude, last.longitude)
                map.addMarker(MarkerOptions().position(userLatLng).title("You are here"))
                if (recenter) {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 15f))
                }
            }
        } else {
            // request permission
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                googleMap?.let { showUserLocationIfPermitted(it, recenter = true) }
            }
        }
    }

    private fun populateRouteDetails(view: View, routeInfo: RouteDetailInfo) {
        val container = view.findViewById<ViewGroup>(R.id.routeStopsContainer) ?: return
        container.removeAllViews()


        // Add start stop
        addRouteStopView(container, routeInfo.start, isStart = true, isEnd = false)

        // Add intermediate stops
        routeInfo.stops.forEach { stop ->
            addRouteStopView(container, stop, isStart = false, isEnd = false)
        }

        // Add end stop
        addRouteStopView(container, routeInfo.end, isStart = false, isEnd = true)
    }

    private fun addRouteStopView(container: ViewGroup, stop: RouteStop, isStart: Boolean, isEnd: Boolean) {
        val context = requireContext()
        val stopView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = if (isEnd) 4 else 16 // Better spacing between stops
                topMargin = if (isStart) 4 else 0
            }
        }

        // Dot container with connecting line
        val dotContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(40, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(0, 0, 20, 0) // More spacing from dot to text
            }
        }

        // Vertical connecting line (only if not the last stop)
        if (!isEnd) {
            val line = View(context).apply {
                setBackgroundColor("#3A3D4E".toColorInt())
                layoutParams = FrameLayout.LayoutParams(3, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    topMargin = 24 // Start line below the dot
                }
            }
            dotContainer.addView(line)
        }

        // Dot indicator (green for start, red for end, blue for others)
        val dot = View(context).apply {
            val color = when {
                isStart -> "#00E676".toColorInt()
                isEnd -> "#FF5252".toColorInt()
                else -> "#4A9FF5".toColorInt()
            }
            setBackgroundColor(color)
            layoutParams = FrameLayout.LayoutParams(14, 14).apply { // Slightly larger dots
                gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.TOP
                topMargin = 6
            }
            // Make it circular
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }

        dotContainer.addView(dot)

        // Text container
        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = 4 // Align text with dot
            }
        }

        val nameText = TextView(context).apply {
            text = stop.name
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f // Slightly larger for readability
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 2, 0, 2)
        }

        textContainer.addView(nameText)

        // ETA badge if present
        if (stop.etaMinutes != null) {
            val etaBadge = TextView(context).apply {
                text = "${stop.etaMinutes} min"
                setTextColor("#8E9AAF".toColorInt())
                textSize = 13f
                setPadding(14, 6, 14, 6)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor("#2A2D3E".toColorInt())
                    cornerRadius = 18f
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setMargins(12, 0, 0, 0) // Left margin for spacing
                }
            }
            stopView.addView(dotContainer)
            stopView.addView(textContainer)
            stopView.addView(etaBadge)
        } else {
            stopView.addView(dotContainer)
            stopView.addView(textContainer)
        }

        container.addView(stopView)
    }

    private fun drawRoute(points: List<LatLng>) {
        val map = googleMap ?: return

        // clear previous overlays to avoid duplication when opening repeatedly
        map.clear()

        // main route polyline using centralized config
        val polylineOptions = PolylineOptions()
            .addAll(points)
            .color(RouteRenderConfig.polylineColorHex.toColorInt())
            .width(RouteRenderConfig.polylineWidth)
            .geodesic(true)

        map.addPolyline(polylineOptions)

        // Add directional arrow markers along the route if enabled
        if (RouteRenderConfig.showDirectionArrows && points.size > 1) {
            updateArrowsForZoom(map, points)
        }

        // Optionally add markers depending on the central config
        if (RouteRenderConfig.showMarkers) {
            when (RouteRenderConfig.markerMode) {
                RouteRenderConfig.MarkerMode.NONE -> {
                    // nothing
                }
                RouteRenderConfig.MarkerMode.START_END -> {
                    val start = points.first()
                    val end = points.last()
                    if (RouteRenderConfig.startMarkerResId != null) {
                        map.addMarker(
                            MarkerOptions().position(start).title("Start")
                                .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.fromResource(RouteRenderConfig.startMarkerResId!!))
                        )
                    } else {
                        map.addMarker(
                            MarkerOptions().position(start).title("Start")
                                .icon(BitmapDescriptorFactory.defaultMarker(RouteRenderConfig.startMarkerHue))
                        )
                    }

                    if (RouteRenderConfig.endMarkerResId != null) {
                        map.addMarker(
                            MarkerOptions().position(end).title("End")
                                .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.fromResource(RouteRenderConfig.endMarkerResId!!))
                        )
                    } else {
                        map.addMarker(
                            MarkerOptions().position(end).title("End")
                                .icon(BitmapDescriptorFactory.defaultMarker(RouteRenderConfig.endMarkerHue))
                        )
                    }
                }
                RouteRenderConfig.MarkerMode.ALL_NUMBERED -> {
                    // Add markers for all stops (simple colored markers)
                    for (i in points.indices) {
                        val pos = points[i]
                        if (RouteRenderConfig.intermediateMarkerResId != null) {
                            map.addMarker(
                                MarkerOptions().position(pos).title("Stop ${i + 1}")
                                    .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.fromResource(RouteRenderConfig.intermediateMarkerResId!!))
                            )
                        } else {
                            // use hue: start gets start hue, end gets end hue, intermediates get intermediateHue
                            val hue = when (i) {
                                0 -> RouteRenderConfig.startMarkerHue
                                points.size - 1 -> RouteRenderConfig.endMarkerHue
                                else -> RouteRenderConfig.intermediateMarkerHue
                            }
                            map.addMarker(
                                MarkerOptions().position(pos).title("Stop ${i + 1}")
                                    .icon(BitmapDescriptorFactory.defaultMarker(hue))
                            )
                        }
                    }
                }
            }
        }

        // Build camera bounds so the whole route fits nicely with padding
        val boundsBuilder = LatLngBounds.builder()
        points.forEach { boundsBuilder.include(it) }
        val bounds = boundsBuilder.build()
        // Slightly larger padding for the small preview card so markers and route aren't cut off
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 160))

        // Improve map UI for preview: hide map toolbar and allow gestures
        map.uiSettings.isMapToolbarEnabled = false
        map.uiSettings.setAllGesturesEnabled(true)

        // Replace idle listener with move listener and bucket updates to improve auto-resize responsiveness
        map.setOnCameraMoveListener {
            if (!RouteRenderConfig.showDirectionArrows || points.size <= 1) return@setOnCameraMoveListener
            val zoom = map.cameraPosition.zoom
            val bucket = zoomBucket(zoom)
            if (bucket != lastArrowZoomBucket) {
                lastArrowZoomBucket = bucket
                updateArrowsForZoom(map, points)
            }
        }
    }

    private fun zoomBucket(zoom: Float): Int {
        // Bucket zoom into 0.25 steps to avoid redrawing too often
        return (zoom * 4f).toInt()
    }

    private fun updateArrowsForZoom(map: GoogleMap, points: List<LatLng>) {
        val zoom = map.cameraPosition.zoom
        val arrowBitmap = createArrowBitmap(zoom)
        routeArrowDrawer.addDirectionalArrows(map, points, arrowBitmap)
    }

    private fun createArrowBitmap(zoom: Float = 14f): com.google.android.gms.maps.model.BitmapDescriptor {
        // Aggressive downscale at low zoom to avoid clutter
        val scaleFactor = when {
            zoom < 12f -> 0.35f
            zoom < 13f -> 0.5f
            zoom < 14f -> 0.6f
            zoom < 15.5f -> 0.9f
            zoom < 17.5f -> 1.2f
            else -> 1.5f
        }

        val baseSize = 80
        val size = (baseSize * scaleFactor).toInt().coerceAtLeast(20)
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        val leftColor = android.graphics.Color.parseColor("#C62828")
        val rightColor = android.graphics.Color.parseColor("#8E0000")
        val outlineColor = android.graphics.Color.parseColor("#4E0000")

        val cx = size / 2f
        val tipY = size * 0.14f
        val baseY = size * 0.88f
        val leftX = size * 0.12f
        val rightX = size * 0.88f

        val leftPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
            color = leftColor
        }
        val leftPath = android.graphics.Path().apply {
            moveTo(cx, tipY)
            lineTo(cx, baseY)
            lineTo(leftX, baseY)
            close()
        }
        canvas.drawPath(leftPath, leftPaint)

        val rightPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
            color = rightColor
        }
        val rightPath = android.graphics.Path().apply {
            moveTo(cx, tipY)
            lineTo(rightX, baseY)
            lineTo(cx, baseY)
            close()
        }
        canvas.drawPath(rightPath, rightPaint)

        val ridgePaint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = (1.0f * scaleFactor).coerceAtLeast(0.8f)
            color = android.graphics.Color.parseColor("#FFFFFF")
            alpha = 40
        }
        canvas.drawLine(cx, tipY + size * 0.02f, cx, baseY - size * 0.02f, ridgePaint)

        val outlinePaint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = (1.5f * scaleFactor).coerceAtLeast(1.2f)
            color = outlineColor
        }
        canvas.drawPath(leftPath, outlinePaint)
        canvas.drawPath(rightPath, outlinePaint)

        return com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}
