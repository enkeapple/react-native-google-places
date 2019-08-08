import { NativeModules } from "react-native";

const RNGooglePlaces = NativeModules.RNGooglePlaces;

export default {
  optionsDefaults: {
    type: "",
    country: "",
    useOverlay: false,
    initialQuery: "",
    useSessionToken: true,
    locationBias: {
      latitudeSW: 0,
      longitudeSW: 0,
      latitudeNE: 0,
      longitudeNE: 0
    },
    locationRestriction: {
      latitudeSW: 0,
      longitudeSW: 0,
      latitudeNE: 0,
      longitudeNE: 0
    }
  },

  openAutocompleteModal(options, placeFields) {
    const nativeOptions = Object.assign(
      {},
      RNGooglePlaces.optionsDefaults,
      options
    );
    return RNGooglePlaces.openAutocompleteModal(
      nativeOptions,
      placeFields || []
    );
  },

  getAutocompletePredictions(query, options = {}) {
    return RNGooglePlaces.getAutocompletePredictions(query, {
      ...RNGooglePlaces.optionsDefaults,
      ...options
    });
  },

  lookUpPlaceByID(placeID, placeFields = []) {
    return RNGooglePlaces.lookUpPlaceByID(placeID, [
      ...RNGooglePlaces.placeFieldsDefaults,
      ...placeFields
    ]);
  }
};
