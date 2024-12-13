package com.example.weatheralertapp.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.weatheralertapp.home.LocationState
import com.example.weatheralertapp.R
import com.example.weatheralertapp.weatherapi.AlertItem
import com.example.weatheralertapp.weatherapi.GeoJsonLocation
import com.example.weatheralertapp.weatherapi.WeatherService
import com.example.weatheralertapp.weatherapi.Constants
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.LinkedList
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// A Cache system for storing weather alerts with a defined expiration time
class WeatherAlertCache {
    data class CacheEntry(
        val alerts: List<AlertItem>,
        val timestamp: Long
    )

    private val cache = mutableMapOf<String, CacheEntry>()
    private val CACHE_DURATION = 30 * 60 * 1000

    // This retrieve alerts for a specific location from the cache
    fun get(location: String): List<AlertItem>? {
        val entry = cache[location] ?: return null
        val isExpired = (System.currentTimeMillis() - entry.timestamp) > CACHE_DURATION
        return if (isExpired) null else entry.alerts
    }

    // Store the alerts for a specific location in the cache
    fun put(location: String, alerts: List<AlertItem>) {
        cache[location] = CacheEntry(alerts, System.currentTimeMillis())
    }
}

// Object to manage API rate-limiting to avoid exceeding usage limits
object ApiRateLimiter {
    private var lastRequestTime = 0L
    private val hourlyRequests = LinkedList<Long>()
    private val dailyRequests = LinkedList<Long>()

    private const val REQUESTS_PER_SECOND = 3
    private const val REQUESTS_PER_HOUR = 25
    private const val REQUESTS_PER_DAY = 500

    private const val HOUR_IN_MS = 60 * 60 * 1000L
    private const val DAY_IN_MS = 24 * HOUR_IN_MS

    // A check for the api limits within the allowable bounds
    @Synchronized
    fun checkRateLimit(): RateLimitStatus {
        val currentTime = System.currentTimeMillis()

        // Clean up old requests
        hourlyRequests.removeAll { it < currentTime - HOUR_IN_MS }
        dailyRequests.removeAll { it < currentTime - DAY_IN_MS }

        // Check if we're within limits
        return when {
            currentTime - lastRequestTime < (1000 / REQUESTS_PER_SECOND) ->
                RateLimitStatus.ExceededPerSecond
            hourlyRequests.size >= REQUESTS_PER_HOUR ->
                RateLimitStatus.ExceededPerHour
            dailyRequests.size >= REQUESTS_PER_DAY ->
                RateLimitStatus.ExceededPerDay
            else -> RateLimitStatus.Allowed
        }
    }

    // Record a request to update the tracking lists
    @Synchronized
    fun recordRequest() {
        val currentTime = System.currentTimeMillis()
        lastRequestTime = currentTime
        hourlyRequests.add(currentTime)
        dailyRequests.add(currentTime)
    }
}

// Representation of the API rate limit statuses
sealed class RateLimitStatus {
    object Allowed : RateLimitStatus()
    object ExceededPerSecond : RateLimitStatus()
    object ExceededPerHour : RateLimitStatus()
    object ExceededPerDay : RateLimitStatus()
}

// Representation of the UI state for alerts
data class AlertsUiState(
    val currentLocation: LocationState = LocationState(),
    val alerts: List<AlertItem> = emptyList(),
    val savedLocations: List<LocationState> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)


// Management of weather alerts and location-related operations
class AlertsViewModel(application: Application) : AndroidViewModel(application) {
    private val cache = WeatherAlertCache()
    private val coroutineScope = viewModelScope + Dispatchers.IO
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    // Added a MutableStateFlow for refresh state
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // HTTP client with logging enabled for debugging API calls
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .build()
    private val retrofit = Retrofit.Builder()
        .baseUrl(Constants.TOMORROW_BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .client(httpClient)
        .build()

    private val weatherService = retrofit.create(WeatherService::class.java)
    private val geocoder = Geocoder(getApplication(), Locale.getDefault())

    private val _uiState = MutableStateFlow(AlertsUiState())
    val uiState: StateFlow<AlertsUiState> = _uiState.asStateFlow()


    init {
        createNotificationChannel()
    }

    // Creation of a notification channel for weather alerts
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Weather Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for weather alerts"
            }
            val notificationManager = getApplication<Application>().getSystemService(
                NotificationManager::class.java
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    // Trigger a refresh of weather alerts for the current location
    fun refreshAlerts(context: Context) {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                val currentLocation = _uiState.value.currentLocation
                if (currentLocation.latitude != 0.0 && currentLocation.longitude != 0.0) {
                    fetchAlertsForLocation(currentLocation.latitude, currentLocation.longitude)
                }
                delay(500)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Error refreshing alerts: ${e.message}") }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    // Fetch the user's current location and update the UI state
    fun fetchCurrentLocation(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@launch
                }

                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    location?.let {
                        updateLocationState(it.latitude, it.longitude)
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to fetch location: ${e.message}", isLoading = false)
                }
            }
        }
    }

    // Update the location state with new latitude and longitude
    private fun updateLocationState(latitude: Double, longitude: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Geocoding operations on IO thread
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                val cityName = addresses?.firstOrNull()?.locality ?: ""
                val country = addresses?.firstOrNull()?.countryName ?: ""

                val newLocation = LocationState(
                    latitude = latitude,
                    longitude = longitude,
                    cityName = cityName,
                    country = country,
                    formattedLocation = "$latitude,$longitude"
                )

                // Switch to Main thread for UI updates
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(currentLocation = newLocation) }
                }

                // Stay on IO thread for network call
                fetchAlertsForLocation(latitude, longitude)
            } catch (e: Exception) {
                // Switch to Main thread for error UI update
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(error = "Failed to update location: ${e.message}", isLoading = false)
                    }
                }
            }
        }
    }

    // Search for a location by its city name and update the UI state
    fun searchLocation(cityName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val addresses = geocoder.getFromLocationName(cityName, 1)
                addresses?.firstOrNull()?.let { address ->
                    updateLocationState(address.latitude, address.longitude)
                } ?: run {
                    _uiState.update {
                        it.copy(error = "Location not found", isLoading = false)
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to search location: ${e.message}", isLoading = false)
                }
            }
        }
    }

    // Geospatial calculations, including distance checks
    object GeoUtils {
        fun isWithinRange(
            centerLat: Double,
            centerLon: Double,
            targetLat: Double,
            targetLon: Double,
            radiusKm: Double = 50.0
        ): Boolean {
            val R = 6371.0 // Earth's radius in kilometers

            val latDistance = Math.toRadians(targetLat - centerLat)
            val lonDistance = Math.toRadians(targetLon - centerLon)

            val a = sin(latDistance / 2) * sin(latDistance / 2) +
                    cos(Math.toRadians(centerLat)) * cos(Math.toRadians(targetLat)) *
                    sin(lonDistance / 2) * sin(lonDistance / 2)

            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            val distance = R * c

            return distance <= radiusKm
        }

        // Check if any point in a polygon is within a given radius of a center point
        fun isPolygonInRange(
            centerLat: Double,
            centerLon: Double,
            coordinates: List<List<List<Double>>>,
            radiusKm: Double = 50.0
        ): Boolean {
            // Iterate through polygon coordinates to see if any are within range
            return coordinates[0].any { coordinate ->
                val targetLon = coordinate[0]
                val targetLat = coordinate[1]
                isWithinRange(centerLat, centerLon, targetLat, targetLon, radiusKm)
            }
        }
    }

    // Added a data class for alert deduplication
    data class AlertKey(
        val title: String,
        val startTime: String,
        val endTime: String,
        val severity: String,
        val location: GeoJsonLocation?
    )

    // Update ViewModel fetch function
    fun fetchAlertsForLocation(latitude: Double, longitude: Double, radiusKm: Double = 50.0) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true, error = null) }

                val locationString = "$latitude,$longitude"

                when (val rateLimitStatus = ApiRateLimiter.checkRateLimit()) {
                    is RateLimitStatus.Allowed -> {
                        ApiRateLimiter.recordRequest()
                        val response = weatherService.getWeatherAlerts(
                            location = locationString,
                            apikey = Constants.TOMORROW_API_KEY
                        )

                        //Set to track unique alerts
                        val uniqueAlerts = mutableSetOf<AlertKey>()

                        val alertsList = response.data.events
                            .filter { event ->
                                // Create unique key for deduplication
                                val alertKey = AlertKey(
                                    title = event.eventValues.title,
                                    startTime = event.startTime,
                                    endTime = event.endTime,
                                    severity = event.severity,
                                    location = event.eventValues.location
                                )

                                // Check if it's within range and not a duplicate
                                val isInRange = event.eventValues.location?.let { location ->
                                    when (location.type) {
                                        "Polygon" -> {
                                            @Suppress("UNCHECKED_CAST")
                                            val coordinates = location.coordinates as? List<List<List<Double>>>
                                            coordinates?.let {
                                                GeoUtils.isPolygonInRange(
                                                    centerLat = latitude,
                                                    centerLon = longitude,
                                                    coordinates = it,
                                                    radiusKm = radiusKm
                                                )
                                            } ?: false
                                        }
                                        "Point" -> {
                                            @Suppress("UNCHECKED_CAST")
                                            val coordinates = location.coordinates as? List<Double>
                                            coordinates?.let {
                                                GeoUtils.isWithinRange(
                                                    centerLat = latitude,
                                                    centerLon = longitude,
                                                    targetLat = it[1],
                                                    targetLon = it[0],
                                                    radiusKm = radiusKm
                                                )
                                            } ?: false
                                        }
                                        else -> false
                                    }
                                } ?: false

                                isInRange && uniqueAlerts.add(alertKey)
                            }
                            .mapNotNull { event ->
                                AlertItem.fromEvent(event)
                            }

                        cache.put(locationString, alertsList)

                        _uiState.update {
                            it.copy(
                                alerts = alertsList,
                                isLoading = false,
                                error = if (alertsList.isEmpty())
                                    "No active alerts within ${radiusKm}km of this location"
                                else null
                            )
                        }
                    }
                    is RateLimitStatus.ExceededPerSecond -> {
                        delay(1000)
                        fetchAlertsForLocation(latitude, longitude)
                    }
                    is RateLimitStatus.ExceededPerHour -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "Hourly API limit reached. Please try again later."
                            )
                        }
                    }
                    is RateLimitStatus.ExceededPerDay -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "Daily API limit reached. Please try again tomorrow."
                            )
                        }
                    }
                }

            } catch (e: Exception) {
                val errorMessage = when {
                    e.message?.contains("429") == true -> "Rate limit exceeded. Please try again later."
                    e.message?.contains("401") == true -> "Invalid API key. Please check your configuration."
                    e.message?.contains("404") == true -> "No data available for this location"
                    else -> "Error fetching weather data: ${e.message}"
                }
                _uiState.update {
                    it.copy(error = errorMessage, isLoading = false)
                }
            }
        }
    }


    // Helper function to convert ISO 8601 string to epoch milliseconds
    @SuppressLint("NewApi")
    private fun String.toEpochMillis(): Long {
        return try {
            java.time.Instant.parse(this).toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    // Save the current location to the list of saved locations
    fun saveCurrentLocation() {
        val currentLocation = _uiState.value.currentLocation
        if (currentLocation.latitude != 0.0 && currentLocation.longitude != 0.0) {
            _uiState.update {
                it.copy(savedLocations = it.savedLocations + currentLocation)
            }
        }
    }

    private fun sendAlertNotification(alert: AlertItem) {
        val context = getApplication<Application>()
        val notificationBuilder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(alert.event)
            .setContentText(alert.description)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notificationManager = NotificationManagerCompat.from(context)
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager.notify(
                System.currentTimeMillis().toInt(),
                notificationBuilder.build()
            )
        }
    }

    companion object {
        private const val ALERT_CHANNEL_ID = "weather_alerts"
    }
}