/*
 * Copyright 2012 osmdroid: M.Kergall
 * Copyright 2012 Hannes Janetzek
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.oscim.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.oscim.core.GeoPoint;
import org.oscim.overlay.ItemizedOverlay;
import org.oscim.overlay.Overlay;
import org.oscim.overlay.OverlayItem;
import org.oscim.overlay.OverlayItem.HotspotPlace;
import org.oscim.overlay.PathOverlay;
import org.oscim.view.MapView;
import org.osmdroid.location.GeocoderNominatim;
import org.osmdroid.overlays.DefaultInfoWindow;
import org.osmdroid.overlays.ExtendedOverlayItem;
import org.osmdroid.overlays.ItemizedOverlayWithBubble;
import org.osmdroid.routing.Route;
import org.osmdroid.routing.RouteProvider;
import org.osmdroid.routing.RouteNode;
import org.osmdroid.routing.provider.MapQuestRouteProvider;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.drawable.Drawable;
import android.location.Address;
import android.os.AsyncTask;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

public class RouteSearch {
	protected Route mRoute;
	protected PathOverlay mRouteOverlay;
	protected ItemizedOverlayWithBubble<ExtendedOverlayItem> mRouteMarkers;
	protected ItemizedOverlayWithBubble<ExtendedOverlayItem> mItineraryMarkers;

	protected GeoPoint mStartPoint, mDestinationPoint;
	protected ArrayList<GeoPoint> mViaPoints;
	protected static int START_INDEX = -2, DEST_INDEX = -1;
	protected ExtendedOverlayItem markerStart, markerDestination;

	private final TileMap tileMap;

	RouteSearch(TileMap tileMap) {

		this.tileMap = tileMap;
		mStartPoint = null;
		mDestinationPoint = null;
		mViaPoints = new ArrayList<GeoPoint>();

		// Itinerary markers:
		ArrayList<ExtendedOverlayItem> waypointsItems = new ArrayList<ExtendedOverlayItem>();
		mItineraryMarkers = new ItemizedOverlayWithBubble<ExtendedOverlayItem>(tileMap.map,
				tileMap, waypointsItems, new ViaPointInfoWindow(R.layout.itinerary_bubble,
						tileMap.map));

		updateIternaryMarkers();

		//Route and Directions
		ArrayList<ExtendedOverlayItem> routeItems = new ArrayList<ExtendedOverlayItem>();
		mRouteMarkers = new ItemizedOverlayWithBubble<ExtendedOverlayItem>(tileMap, routeItems,
				tileMap.map);

		tileMap.map.getOverlays().add(mRouteMarkers);
		tileMap.map.getOverlays().add(mItineraryMarkers);
	}

	/**
	 * Reverse Geocoding
	 * @param p
	 *            ...
	 * @return ...
	 */
	public String getAddress(GeoPoint p) {
		GeocoderNominatim geocoder = new GeocoderNominatim(tileMap);
		String theAddress;
		try {
			double dLatitude = p.getLatitude();
			double dLongitude = p.getLongitude();
			List<Address> addresses = geocoder.getFromLocation(dLatitude, dLongitude, 1);
			StringBuilder sb = new StringBuilder();
			if (addresses.size() > 0) {
				Address address = addresses.get(0);
				int n = address.getMaxAddressLineIndex();
				for (int i = 0; i <= n; i++) {
					if (i != 0)
						sb.append(", ");
					sb.append(address.getAddressLine(i));
				}
				theAddress = new String(sb.toString());
			} else {
				theAddress = null;
			}
		} catch (IOException e) {
			theAddress = null;
		}
		if (theAddress != null) {
			return theAddress;
		}
		return "";
	}

	// Async task to reverse-geocode the marker position in a separate thread:
	class GeocodingTask extends AsyncTask<Object, Void, String> {
		ExtendedOverlayItem marker;

		@Override
		protected String doInBackground(Object... params) {
			marker = (ExtendedOverlayItem) params[0];
			return getAddress(marker.getPoint());
		}

		@Override
		protected void onPostExecute(String result) {
			marker.setDescription(result);
			//itineraryMarkers.showBubbleOnItem(???, map); //open bubble on the item
		}
	}

	/* add (or replace) an item in markerOverlays. p position. */
	public ExtendedOverlayItem putMarkerItem(ExtendedOverlayItem item, GeoPoint p, int index,
			int titleResId, int markerResId, int iconResId) {

		if (item != null)
			mItineraryMarkers.removeItem(item);

		Drawable marker = ItemizedOverlay.makeMarker(App.res, markerResId,
				HotspotPlace.BOTTOM_CENTER);

		String title = App.res.getString(titleResId);

		ExtendedOverlayItem overlayItem = new ExtendedOverlayItem(title, "", p);

		overlayItem.setMarker(marker);

		if (iconResId != -1)
			overlayItem.setImage(App.res.getDrawable(iconResId));

		overlayItem.setRelatedObject(Integer.valueOf(index));

		mItineraryMarkers.addItem(overlayItem);

		tileMap.map.redrawMap(true);

		//Start geocoding task to update the description of the marker with its address:
		new GeocodingTask().execute(overlayItem);
		return overlayItem;
	}

	public void addViaPoint(GeoPoint p) {
		mViaPoints.add(p);
		putMarkerItem(null, p, mViaPoints.size() - 1,
				R.string.viapoint, R.drawable.marker_via, -1);
	}

	public void removePoint(int index) {
		if (index == START_INDEX)
			mStartPoint = null;
		else if (index == DEST_INDEX)
			mDestinationPoint = null;
		else
			mViaPoints.remove(index);

		getRouteAsync();
		updateIternaryMarkers();
	}

	public void updateIternaryMarkers() {
		mItineraryMarkers.removeAllItems();
		//Start marker:
		if (mStartPoint != null) {
			markerStart = putMarkerItem(null, mStartPoint, START_INDEX,
					R.string.departure, R.drawable.marker_departure, -1);
		}
		//Via-points markers if any:
		for (int index = 0; index < mViaPoints.size(); index++) {
			putMarkerItem(null, mViaPoints.get(index), index,
					R.string.viapoint, R.drawable.marker_via, -1);
		}
		//Destination marker if any:
		if (mDestinationPoint != null) {
			markerDestination = putMarkerItem(null, mDestinationPoint, DEST_INDEX,
					R.string.destination,
					R.drawable.marker_destination, -1);
		}
	}

	//------------ Route and Directions

	private void putRouteNodes(Route route) {
		mRouteMarkers.removeAllItems();

		Drawable marker = ItemizedOverlay.makeMarker(App.res, R.drawable.marker_node, null);

		int n = route.nodes.size();
		//TypedArray iconIds = App.res.obtainTypedArray(R.array.direction_icons);
		for (int i = 0; i < n; i++) {
			RouteNode node = route.nodes.get(i);
			String instructions = (node.instructions == null ? "" : node.instructions);
			ExtendedOverlayItem nodeMarker = new ExtendedOverlayItem(
					"Step " + (i + 1), instructions, node.location);

			nodeMarker.setSubDescription(route.getLengthDurationText(node.length, node.duration));
			nodeMarker.setMarkerHotspot(OverlayItem.HotspotPlace.CENTER);
			nodeMarker.setMarker(marker);
			//			int iconId = iconIds.getResourceId(node.mManeuverType, R.drawable.ic_empty);
			//			if (iconId != R.drawable.ic_empty) {
			//				Drawable icon = App.res.getDrawable(iconId);
			//				nodeMarker.setImage(icon);
			//			}
			mRouteMarkers.addItem(nodeMarker);
		}
	}

	void updateRouteMarkers(Route route) {
		mRouteMarkers.removeAllItems();
		List<Overlay> mapOverlays = tileMap.map.getOverlays();
		if (mRouteOverlay != null) {
			mapOverlays.remove(mRouteOverlay);
		}
		if (route == null)
			return;
		if (route.status == Route.STATUS_DEFAULT)
			Toast.makeText(tileMap, "We have a problem to get the route",
					Toast.LENGTH_SHORT).show();
		mRouteOverlay = RouteProvider.buildRouteOverlay(tileMap.map, route);
		Overlay removedOverlay = mapOverlays.set(1, mRouteOverlay);
		//we set the route overlay at the "bottom", just above the MapEventsOverlay,
		//to avoid covering the other overlays.
		mapOverlays.add(removedOverlay);
		putRouteNodes(route);

		tileMap.map.redrawMap(true);

		//Set route info in the text view:

		//	((TextView) findViewById(R.id.routeInfo)).setText(route.getLengthDurationText(-1));
	}

	void removeRoutePath() {
		List<Overlay> mapOverlays = tileMap.map.getOverlays();
		if (mRouteOverlay != null) {
			mapOverlays.remove(mRouteOverlay);
		}
		tileMap.map.redrawMap(true);
	}

	void removeRouteNodes() {
		mRouteMarkers.removeAllItems();
		tileMap.map.redrawMap(true);
	}

	void removeAllOverlay() {
		List<Overlay> mapOverlays = tileMap.map.getOverlays();
		if (mRouteOverlay != null) {
			mapOverlays.remove(mRouteOverlay);
		}
		if (mRouteMarkers != null) {
			mapOverlays.remove(mRouteMarkers);
		}
		final ArrayList<ExtendedOverlayItem> routeItems = new ArrayList<ExtendedOverlayItem>();
		mRouteMarkers = new ItemizedOverlayWithBubble<ExtendedOverlayItem>(tileMap, routeItems,
				tileMap.map);
		tileMap.map.getOverlays().add(mRouteMarkers);

		//removePoint(-2);
		removePoint(-1);
		tileMap.map.redrawMap(true);
	}

	/**
	 * Async task to get the route in a separate thread.
	 */
	class UpdateRouteTask extends AsyncTask<WayPoints, Void, Route> {
		@Override
		protected Route doInBackground(WayPoints... wp) {
			WayPoints waypoints = wp[0];
			//RouteManager routeManager = new GoogleRouteManager();
			//RouteManager routeManager = new OSRMRouteManager();
			RouteProvider routeManager = new MapQuestRouteProvider();
			Locale locale = Locale.getDefault();
			routeManager.addRequestOption("locale=" + locale.getLanguage() + "_"
					+ locale.getCountry());
			return routeManager.getRoute(waypoints);
		}

		@Override
		protected void onPostExecute(Route result) {
			mRoute = result;
			updateRouteMarkers(result);
		}
	}

	class WayPoints extends ArrayList<GeoPoint> {
		public WayPoints(int i) {
			super(i);
		}

		private static final long serialVersionUID = 1L;
	}

	public void getRouteAsync() {
		mRoute = null;
		if (mStartPoint == null || mDestinationPoint == null) {
			updateRouteMarkers(mRoute);
			return;
		}
		WayPoints waypoints = new WayPoints(2);
		waypoints.add(mStartPoint);
		//add intermediate via points:
		for (GeoPoint p : mViaPoints) {
			waypoints.add(p);
		}
		waypoints.add(mDestinationPoint);
		new UpdateRouteTask().execute(waypoints);
	}

	GeoPoint tempClickedGeoPoint; //any other way to pass the position to the menu ???

	boolean longPress(GeoPoint p) {
		tempClickedGeoPoint = p;
		return true;
	}

	void singleTapUp() {
		mRouteMarkers.hideBubble();
		mItineraryMarkers.hideBubble();
	}

	boolean onContextItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_departure:
			mStartPoint = tempClickedGeoPoint;
			markerStart = putMarkerItem(markerStart, mStartPoint, START_INDEX,
					R.string.departure, R.drawable.marker_departure, -1);
			getRouteAsync();
			return true;

		case R.id.menu_destination:
			mDestinationPoint = tempClickedGeoPoint;
			markerDestination = putMarkerItem(markerDestination, mDestinationPoint, DEST_INDEX,
					R.string.destination,
					R.drawable.marker_destination, -1);
			getRouteAsync();
			return true;

		case R.id.menu_viapoint:
			GeoPoint viaPoint = tempClickedGeoPoint;
			addViaPoint(viaPoint);
			getRouteAsync();
			return true;

		case R.id.menu_clear:
			AlertDialog.Builder builder = new AlertDialog.Builder(tileMap);
			//				if (mRouteOverlay != null) {
			//					mapOverlays.remove(mRouteOverlay);
			//				}
			//				if (mRouteNodeMarkers != null) {
			//					mapOverlays.remove(mRouteNodeMarkers);
			//				}
			List<Overlay> mapOverlays = tileMap.map.getOverlays();
			ArrayList<String> list = new ArrayList<String>();

			if (!mapOverlays.contains(mRouteOverlay) && mRouteMarkers.size() != 0) {
				list.clear();
				list.add("Clear Route Node Only");
				list.add("Clear All");
			} else if (mRouteMarkers.size() == 0 && mapOverlays.contains(mRouteOverlay)) {
				list.clear();
				list.add("Clear Route Only");
				list.add("Clear All");
			} else if (!mapOverlays.contains(mRouteOverlay) && mRouteMarkers.size() == 0
					&& markerDestination == null) {
				list.clear();
				list.add("Nothing to Clear");
			} else if (!mapOverlays.contains(mRouteOverlay) && mRouteMarkers.size() == 0
					&& markerDestination != null) {
				list.clear();
				list.add("Clear All");
			} else {
				list.clear();
				list.add("Clear Route Node Only");
				list.add("Clear Route Only");
				list.add("Clear All");
			}
			final String[] test = new String[list.size()];
			for (int i = 0; i < list.size(); i++) {
				test[i] = list.get(i);
			}
			builder.setTitle("Clear");
			builder.setItems(test, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					if (test[which].equals("Clear Route Only")) {
						removeRoutePath();
					} else if (test[which].equals("Clear Route Node Only")) {
						removeRouteNodes();
					} else if (test[which].equals("Clear All")) {
						removeAllOverlay();
					}
					// The 'which' argument contains the index position
					// of the selected item
				}
			});
			AlertDialog alertDialog = builder.create();

			alertDialog.show();
			return true;

		default:
		}
		return false;
	}

	class ViaPointInfoWindow extends DefaultInfoWindow {

		int mSelectedPoint;

		public ViaPointInfoWindow(int layoutResId, MapView mapView) {
			super(layoutResId, mapView);

			Button btnDelete = (Button) (mView.findViewById(R.id.bubble_delete));
			btnDelete.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View view) {
					removePoint(mSelectedPoint);
					close();
				}
			});
		}

		@Override
		public void onOpen(ExtendedOverlayItem item) {
			mSelectedPoint = ((Integer) item.getRelatedObject()).intValue();
			super.onOpen(item);
		}

	}
}
