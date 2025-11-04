package com.example.aginavigation.ui.map

import android.os.Bundle
import android.view.View
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import com.example.aginavigation.R
import com.example.aginavigation.ui.util.RouteArrowDrawer
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions

// This class correctly implements OnMapReadyCallback to get the GoogleMap object.
class MapFragment : Fragment(R.layout.fragment_map), OnMapReadyCallback {

    private lateinit var googleMap: GoogleMap
    private var routePoints: ArrayList<LatLng>? = null
    private val routeArrowDrawer = RouteArrowDrawer()
    private var lastArrowZoomBucket: Int? = null

    // This is where you initialize the map fragment.
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        // Retrieve route points if passed from RoutesFragment
        @Suppress("DEPRECATION")
        routePoints = arguments?.getParcelableArrayList("route_points")

        // The SupportMapFragment is a container for the map. We find it by its ID.
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment

        // Request the map; onMapReady will be called when it's loaded.
        mapFragment.getMapAsync(this)
    }

    /**
     * This method is called when the map is ready to be used.
     * You can now add markers, move the camera, and customize the map.
     */
    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // If route points were provided, draw them; otherwise show a default marker.
        routePoints?.let { points ->
            if (points.isNotEmpty()) {
                drawRoute(points)
                return
            }
        }

        // Fallback example: Add a marker for Legazpi and zoom the camera in on it.
        val legazpi = LatLng(13.1362, 123.7380)
        googleMap.addMarker(MarkerOptions().position(legazpi).title("Marker in Legazpi"))
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(legazpi, 14f))
    }

    private fun drawRoute(points: List<LatLng>) {
        if (points.isEmpty()) return

        // Draw the polyline
        googleMap.addPolyline(
            PolylineOptions()
                .addAll(points)
                .color("#FF5722".toColorInt())
                .width(8f)
                .geodesic(true)
        )

        // Add directional arrows to show route flow
        updateArrowsForZoom(googleMap, points)

        // Build bounds to fit the entire route on screen
        val boundsBuilder = LatLngBounds.builder()
        points.forEach { boundsBuilder.include(it) }
        val bounds = boundsBuilder.build()

        // Animate camera to fit route with padding
        googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))

        // Add zoom change listener to redraw arrows at appropriate size
        googleMap.setOnCameraMoveListener {
            val zoom = googleMap.cameraPosition.zoom
            val bucket = zoomBucket(zoom)
            if (bucket != lastArrowZoomBucket) {
                lastArrowZoomBucket = bucket
                updateArrowsForZoom(googleMap, points)
            }
        }
    }

    private fun zoomBucket(zoom: Float): Int = (zoom * 4f).toInt()

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
