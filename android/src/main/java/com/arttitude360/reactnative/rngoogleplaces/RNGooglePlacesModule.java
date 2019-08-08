package com.arttitude360.reactnative.rngoogleplaces;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.tasks.Task;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.AutocompletePrediction;
import com.google.android.libraries.places.api.model.AutocompleteSessionToken;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.api.model.RectangularBounds;
import com.google.android.libraries.places.api.model.TypeFilter;
import com.google.android.libraries.places.api.net.FetchPlaceRequest;
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest;
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsResponse;
import com.google.android.libraries.places.api.net.PlacesClient;
import com.google.android.libraries.places.widget.Autocomplete;
import com.google.android.libraries.places.widget.AutocompleteActivity;
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@ReactModule(name = "RNGooglePlaces")
public class RNGooglePlacesModule extends ReactContextBaseJavaModule implements ActivityEventListener {

    private ReactApplicationContext reactContext;
    private Promise pendingPromise;
    private List<Place.Field> lastSelectedFields;
    public static final String TAG = "RNGooglePlaces";
    private PlacesClient placesClient;

    public static int AUTOCOMPLETE_REQUEST_CODE = 360;
    public static String REACT_CLASS = "RNGooglePlaces";

    public RNGooglePlacesModule(ReactApplicationContext reactContext) {
        super(reactContext);

        int resId = reactContext.getResources().getIdentifier("places_api_key", "string", reactContext.getPackageName());
        String apiKey = "";
        if (resId != 0) {
            apiKey = reactContext.getString(resId);
        } else {
            Log.e(TAG, "Can't find string resource 'places_api_key', google places api will not work");
        }

        // Setup Places Client
        if (!Places.isInitialized() && !apiKey.equals("")) {
            Places.initialize(reactContext.getApplicationContext(), apiKey);
        }

        placesClient = Places.createClient(reactContext.getApplicationContext());

        this.reactContext = reactContext;
        this.reactContext.addActivityEventListener(this);
    }

    @Override
    public String getName() {
        return REACT_CLASS;
    }

    /**
     * Called after the autocomplete activity has finished to return its result.
     */
    @Override
    public void onActivityResult(Activity activity, final int requestCode, final int resultCode, final Intent intent) {

        // Check that the result was from the autocomplete widget.
        if (requestCode == AUTOCOMPLETE_REQUEST_CODE) {
            if (resultCode == AutocompleteActivity.RESULT_OK) {
                Place place = Autocomplete.getPlaceFromIntent(intent);
                WritableMap map = propertiesMapForPlace(place, this.lastSelectedFields);

                resolvePromise(map);
            } else if (resultCode == AutocompleteActivity.RESULT_ERROR) {
                Status status = Autocomplete.getStatusFromIntent(intent);

                rejectPromise("E_RESULT_ERROR", new Error(status.getStatusMessage()));
            } else if (resultCode == AutocompleteActivity.RESULT_CANCELED) {
                rejectPromise("E_USER_CANCELED", new Error("Search cancelled"));
            }
        }
    }

    @ReactMethod
    public void openAutocompleteModal(ReadableMap options, ReadableArray fields, Promise promise) {

        if (!Places.isInitialized()) {
            promise.reject("E_API_KEY_ERROR", new Error("No API key defined in gradle.properties or errors initializing Places"));
            return;
        }

        Activity currentActivity = getCurrentActivity();

        if (currentActivity == null) {
            promise.reject("E_ACTIVITY_DOES_NOT_EXIST", new Error("Activity doesn't exist"));
            return;
        }

        this.pendingPromise = promise;
        this.lastSelectedFields = getPlaceFields(fields.toArrayList(), false);
        AutocompleteActivityMode mode = options.hasKey("useOverlay") && options.getBoolean("useOverlay") ? AutocompleteActivityMode.OVERLAY : AutocompleteActivityMode.FULLSCREEN;
        Autocomplete.IntentBuilder autocompleteIntent = new Autocomplete.IntentBuilder(mode, this.lastSelectedFields);

        RectangularBounds locationBias = getBoundsFromMap(options, "locationBias");
        if (locationBias != null) {
            autocompleteIntent.setLocationBias(locationBias);
        }

        RectangularBounds locationRestriction = getBoundsFromMap(options, "locationRestriction");
        if (locationRestriction != null) {
            autocompleteIntent.setLocationRestriction(locationRestriction);
        }

        if (options.hasKey("country")) {
            autocompleteIntent.setCountry(options.getString("country"));
        }

        if (options.hasKey("initialQuery")) {
            autocompleteIntent.setInitialQuery(options.getString("initialQuery"));
        }

        if (options.hasKey("type")) {
            String type = options.getString("type");
            if (type != null) {
                autocompleteIntent.setTypeFilter(getFilterType(type));
            }
        }

        currentActivity.startActivityForResult(autocompleteIntent.build(this.reactContext.getApplicationContext()), AUTOCOMPLETE_REQUEST_CODE);
    }

    @ReactMethod
    public void getAutocompletePredictions(String query, ReadableMap options, final Promise promise) {
        this.pendingPromise = promise;

        if (!Places.isInitialized()) {
            promise.reject("E_API_KEY_ERROR", new Error("No API key defined in gradle.properties or errors initializing Places"));
            return;
        }

        String type = options.getString("type");
        String country = options.getString("country");
        country = country.isEmpty() ? null : country;
        boolean useSessionToken = options.getBoolean("useSessionToken");

        ReadableMap locationBias = options.getMap("locationBias");
        double biasToLatitudeSW = locationBias.getDouble("latitudeSW");
        double biasToLongitudeSW = locationBias.getDouble("longitudeSW");
        double biasToLatitudeNE = locationBias.getDouble("latitudeNE");
        double biasToLongitudeNE = locationBias.getDouble("longitudeNE");

        ReadableMap locationRestriction = options.getMap("locationRestriction");
        double restrictToLatitudeSW = locationRestriction.getDouble("latitudeSW");
        double restrictToLongitudeSW = locationRestriction.getDouble("longitudeSW");
        double restrictToLatitudeNE = locationRestriction.getDouble("latitudeNE");
        double restrictToLongitudeNE = locationRestriction.getDouble("longitudeNE");

        FindAutocompletePredictionsRequest.Builder requestBuilder =
                FindAutocompletePredictionsRequest.builder()
                        .setQuery(query);

        if (country != null) {
            requestBuilder.setCountry(country);
        }

        if (biasToLatitudeSW != 0 && biasToLongitudeSW != 0 && biasToLatitudeNE != 0 && biasToLongitudeNE != 0) {
            requestBuilder.setLocationBias(RectangularBounds.newInstance(
                    new LatLng(biasToLatitudeSW, biasToLongitudeSW),
                    new LatLng(biasToLatitudeNE, biasToLongitudeNE)));
        }

        if (restrictToLatitudeSW != 0 && restrictToLongitudeSW != 0 && restrictToLatitudeNE != 0 && restrictToLongitudeNE != 0) {
            requestBuilder.setLocationRestriction(RectangularBounds.newInstance(
                    new LatLng(restrictToLatitudeSW, restrictToLongitudeSW),
                    new LatLng(restrictToLatitudeNE, restrictToLongitudeNE)));
        }

        requestBuilder.setTypeFilter(getFilterType(type));

        if (useSessionToken) {
            requestBuilder.setSessionToken(AutocompleteSessionToken.newInstance());
        }

        Task<FindAutocompletePredictionsResponse> task =
                placesClient.findAutocompletePredictions(requestBuilder.build());

        task.addOnSuccessListener(
                (response) -> {
                    if (response.getAutocompletePredictions().size() == 0) {
                        WritableArray emptyResult = Arguments.createArray();
                        promise.resolve(emptyResult);
                        return;
                    }

                    WritableArray predictionsList = Arguments.createArray();

                    for (AutocompletePrediction prediction : response.getAutocompletePredictions()) {
                        WritableMap map = Arguments.createMap();
                        map.putString("fullText", prediction.getFullText(null).toString());
                        map.putString("primaryText", prediction.getPrimaryText(null).toString());
                        map.putString("secondaryText", prediction.getSecondaryText(null).toString());
                        map.putString("placeID", prediction.getPlaceId().toString());

                        if (prediction.getPlaceTypes().size() > 0) {
                            List<String> types = new ArrayList<>();
                            for (Place.Type placeType : prediction.getPlaceTypes()) {
                                types.add(RNGooglePlacesPlaceTypeMapper.getTypeSlug(placeType));
                            }
                            map.putArray("types", Arguments.fromArray(types.toArray(new String[0])));
                        }

                        predictionsList.pushMap(map);
                    }

                    promise.resolve(predictionsList);

                });

        task.addOnFailureListener(
                (exception) -> {
                    promise.reject("E_AUTOCOMPLETE_ERROR", new Error(exception.getMessage()));
                });
    }

    @ReactMethod
    public void lookUpPlaceByID(String placeID, ReadableArray fields, final Promise promise) {
        this.pendingPromise = promise;

        if (!Places.isInitialized()) {
            promise.reject("E_API_KEY_ERROR", new Error("No API key defined in gradle.properties or errors initializing Places"));
            return;
        }

        List<Place.Field> selectedFields = getPlaceFields(fields.toArrayList(), true);

        FetchPlaceRequest request = FetchPlaceRequest.builder(placeID, selectedFields).build();

        placesClient.fetchPlace(request).addOnSuccessListener((response) -> {
            Place place = response.getPlace();
            WritableMap map = propertiesMapForPlace(place, selectedFields);

            promise.resolve(map);
        }).addOnFailureListener((exception) -> {
            promise.reject("E_PLACE_DETAILS_ERROR", new Error(exception.getMessage()));
        });
    }

    private WritableMap propertiesMapForPlace(Place place, List<Place.Field> selectedFields) {
        // Display attributions if required.
        // CharSequence attributions = place.getAttributions();

        WritableMap map = Arguments.createMap();

        if (selectedFields.contains(Place.Field.LAT_LNG)) {
            WritableMap locationMap = Arguments.createMap();
            locationMap.putDouble("latitude", place.getLatLng().latitude);
            locationMap.putDouble("longitude", place.getLatLng().longitude);

            map.putMap("location", locationMap);
        }

        if (selectedFields.contains(Place.Field.NAME)) {
            map.putString("name", place.getName());
        }

        if (selectedFields.contains(Place.Field.ADDRESS)) {
            if (!TextUtils.isEmpty(place.getAddress())) {
                map.putString("address", place.getAddress());
            } else {
                map.putString("address", "");
            }
        }

        if (selectedFields.contains(Place.Field.PHONE_NUMBER)) {
            if (!TextUtils.isEmpty(place.getPhoneNumber())) {
                map.putString("phoneNumber", place.getPhoneNumber());
            } else {
                map.putString("phoneNumber", "");
            }
        }

        if (selectedFields.contains(Place.Field.WEBSITE_URI)) {
            if (null != place.getWebsiteUri()) {
                map.putString("website", place.getWebsiteUri().toString());
            } else {
                map.putString("website", "");
            }
        }

        if (selectedFields.contains(Place.Field.ID)) {
            map.putString("placeID", place.getId());
        }

        if (place.getAttributions() != null) {
            List<String> attributions = new ArrayList<>(place.getAttributions());
            map.putArray("attributions", Arguments.fromArray(attributions.toArray(new String[0])));
        } else {
            WritableArray emptyResult = Arguments.createArray();
            map.putArray("attributions", emptyResult);
        }

        if (selectedFields.contains(Place.Field.TYPES)) {
            if (place.getTypes() != null) {
                List<String> types = new ArrayList<>();
                for (Place.Type placeType : place.getTypes()) {
                    types.add(RNGooglePlacesPlaceTypeMapper.getTypeSlug(placeType));
                }
                map.putArray("types", Arguments.fromArray(types.toArray(new String[0])));
            } else {
                WritableArray emptyResult = Arguments.createArray();
                map.putArray("types", emptyResult);
            }
        }

        if (selectedFields.contains(Place.Field.VIEWPORT)) {
            if (place.getViewport() != null) {
                WritableMap viewportMap = Arguments.createMap();
                viewportMap.putDouble("latitudeNE", place.getViewport().northeast.latitude);
                viewportMap.putDouble("longitudeNE", place.getViewport().northeast.longitude);
                viewportMap.putDouble("latitudeSW", place.getViewport().southwest.latitude);
                viewportMap.putDouble("longitudeSW", place.getViewport().southwest.longitude);

                map.putMap("viewport", viewportMap);
            } else {
                WritableMap emptyResult = Arguments.createMap();
                map.putMap("viewport", emptyResult);
            }
        }

        if (selectedFields.contains(Place.Field.PRICE_LEVEL)) {
            if (place.getPriceLevel() != null) {
                map.putInt("priceLevel", place.getPriceLevel());
            } else {
                map.putInt("priceLevel", 0);
            }
        }

        if (selectedFields.contains(Place.Field.RATING)) {
            if (place.getRating() != null) {
                map.putDouble("rating", place.getRating());
            } else {
                map.putDouble("rating", 0);
            }
        }

        if (selectedFields.contains(Place.Field.OPENING_HOURS)) {
            if (place.getOpeningHours() != null) {
                List<String> openingHours = new ArrayList<>(place.getOpeningHours().getWeekdayText());
                map.putArray("openingHours", Arguments.fromArray(openingHours.toArray(new String[0])));
            } else {
                WritableArray emptyResult = Arguments.createArray();
                map.putArray("openingHours", emptyResult);
            }
        }

        if (selectedFields.contains(Place.Field.PLUS_CODE)) {
            if (place.getPlusCode() != null) {
                WritableMap plusCodeMap = Arguments.createMap();
                plusCodeMap.putString("compoundCode", place.getPlusCode().getCompoundCode());
                plusCodeMap.putString("globalCode", place.getPlusCode().getGlobalCode());
                map.putMap("plusCode", plusCodeMap);
            } else {
                WritableMap emptyResult = Arguments.createMap();
                map.putMap("plusCode", emptyResult);
            }
        }

        if (selectedFields.contains(Place.Field.USER_RATINGS_TOTAL)) {
            if (place.getUserRatingsTotal() != null) {
                map.putInt("userRatingsTotal", place.getUserRatingsTotal());
            } else {
                map.putInt("userRatingsTotal", 0);
            }
        }

        return map;
    }

    @Nullable
    private TypeFilter getFilterType(String type) {
        TypeFilter mappedFilter;

        switch (type) {
            case "geocode":
                mappedFilter = TypeFilter.GEOCODE;
                break;
            case "address":
                mappedFilter = TypeFilter.ADDRESS;
                break;
            case "establishment":
                mappedFilter = TypeFilter.ESTABLISHMENT;
                break;
            case "regions":
                mappedFilter = TypeFilter.REGIONS;
                break;
            case "cities":
                mappedFilter = TypeFilter.CITIES;
                break;
            default:
                mappedFilter = null;
                break;
        }

        return mappedFilter;
    }

    private List<Place.Field> getPlaceFields(ArrayList<Object> placeFields, boolean isCurrentOrFetchPlace) {
        List<Place.Field> selectedFields = new ArrayList<>();

        if (placeFields.size() == 0 && !isCurrentOrFetchPlace) {
            return Arrays.asList(Place.Field.values());
        }

        if (placeFields.size() == 0 && isCurrentOrFetchPlace) {
            List<Place.Field> allPlaceFields = new ArrayList<>(Arrays.asList(Place.Field.values()));
            allPlaceFields.removeAll(Arrays.asList(Place.Field.OPENING_HOURS, Place.Field.PHONE_NUMBER, Place.Field.WEBSITE_URI));

            return allPlaceFields;
        }

        for (Object placeField : placeFields) {
            if (RNGooglePlacesPlaceFieldEnum.findByFieldKey(placeField.toString()) != null) {
                selectedFields.add(RNGooglePlacesPlaceFieldEnum.findByFieldKey(placeField.toString()).getField());
            }
        }

        if (placeFields.size() != 0 && isCurrentOrFetchPlace) {
            selectedFields.removeAll(Arrays.asList(Place.Field.OPENING_HOURS, Place.Field.PHONE_NUMBER, Place.Field.WEBSITE_URI));
        }

        return selectedFields;
    }

    private boolean checkPermission(String permission) {
        Activity currentActivity = getCurrentActivity();

        boolean hasPermission =
                ContextCompat.checkSelfPermission(this.reactContext.getApplicationContext(), permission) == PackageManager.PERMISSION_GRANTED;
        if (!hasPermission && currentActivity != null) {
            ActivityCompat.requestPermissions(currentActivity, new String[]{permission}, 0);
        }
        return hasPermission;
    }


    private void rejectPromise(String code, Error err) {
        if (this.pendingPromise != null) {
            this.pendingPromise.reject(code, err);
            this.pendingPromise = null;
        }
    }

    private void resolvePromise(Object data) {
        if (this.pendingPromise != null) {
            this.pendingPromise.resolve(data);
            this.pendingPromise = null;
        }
    }

    private String findPlaceTypeLabelByPlaceTypeId(Integer id) {
        return RNGooglePlacesPlaceTypeEnum.findByTypeId(id).getLabel();
    }

    @Override
    public void onNewIntent(Intent intent) {
    }

    private String getStringFromMap(ReadableMap map, String key) {
        if (!map.hasKey(key)) return null;
        String value = map.getString(key);
        return value.isEmpty() ? null : value;
    }

    private RectangularBounds getBoundsFromMap(ReadableMap in, String key) {
        if (!in.hasKey(key)) return null;
        ReadableMap map = in.getMap(key);
        if (map == null) return null;
        if (map.hasKey("latitudeSW") && map.hasKey("longitudeSW") && map.hasKey("latitudeNE") && map.hasKey("longitudeNE")) {
            LatLng sw = new LatLng(map.getDouble("latitudeSW"), map.getDouble("longitudeSW"));
            LatLng ne = new LatLng(map.getDouble("latitudeNE"), map.getDouble("longitudeNE"));
            return RectangularBounds.newInstance(sw, ne);
        }
        return null;
    }
}
