package com.example.osmproject;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.telecom.Call;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.ItemizedOverlay;
import org.osmdroid.views.overlay.ItemizedOverlayWithFocus;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.OverlayItem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ExecutionException;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

public class MainActivity extends AppCompatActivity implements HttpCallback {
    private final int REQUEST_PERMISSIONS_REQUEST_CODE = 1;
    private MapView map = null;

    TextView text;

    ArrayList<OverlayItem> items;
    HashMap itemsMap;

    LocationListener locationListener;
    LocationManager locationManager;
    GeoPoint currentLocation;
    ItemizedOverlayWithFocus<OverlayItem> currentLocationOverlay;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //Osmdroid configuration
        Context ctx = getApplicationContext();
        Configuration.getInstance().load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx));
        
        setContentView(R.layout.activity_main);

        map = (MapView) findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        text = (TextView) findViewById(R.id.text);
        String overpass;
        String params;
        
        //Overpass API query
        overpass = "http://www.overpass-api.de/api/interpreter?data=[out:json];";
        params = "relation(2904797);\n" +
                "map_to_area->.a;\n" +
                "node[tourism](area .a);\n" +
                "out;";

        AsyncTask<String, Void, JSONObject> request = new HttpRequestHelper(this);
        request.execute(overpass + params);

        //Overlay items
        items = new ArrayList<OverlayItem>();
        itemsMap = new HashMap();

        //GPS module
        locationListener = new MyLocationListener();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, locationListener);
        Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        Log.i("last known location is ", location.toString());
        text.setText(location.toString());
        if( location != null ) {
            currentLocation = new GeoPoint(location.getLatitude(), location.getLongitude());
        }

        Button button = (Button) findViewById(R.id.btnsignin);

        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                startActivity(intent);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        map.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        map.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        ArrayList<String> permissionsToRequest = new ArrayList<>();
        for (int i = 0; i < grantResults.length; i++) {
            permissionsToRequest.add(permissions[i]);
        }
        if (permissionsToRequest.size() > 0) {
            ActivityCompat.requestPermissions(
                    this,
                    permissionsToRequest.toArray(new String[0]),
                    REQUEST_PERMISSIONS_REQUEST_CODE);
        }
    }

    private class HttpRequestHelper extends AsyncTask<String, Void, JSONObject> {
        private HttpCallback callback;
        public HttpRequestHelper(HttpCallback callback){
            this.callback = callback;
        }
        @Override
        protected void onPreExecute()
        {
            super.onPreExecute();
        }
        @Override
        protected JSONObject doInBackground(String... urlString)
        {
            try {
                HttpURLConnection urlConnection = null;
                URL url = new URL(urlString[0]);
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("GET");
                urlConnection.setReadTimeout(10000);
                urlConnection.setConnectTimeout(15000);
                urlConnection.setDoOutput(true);
                urlConnection.connect();

                BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream()));
                StringBuilder sb = new StringBuilder();

                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line + "\n");
                }
                br.close();

                String jsonString = sb.toString();
                Log.i("JSON: ", jsonString);

                return new JSONObject(jsonString);
            } catch (IOException e) {
                Log.e("http", "IOException");
            } catch (JSONException e){
                Log.e("http", "JSONException");
            }
            return new JSONObject();
        }

        @Override
        protected void onPostExecute(JSONObject result)
        {
            try {
                callback.refreshMapNodes(result);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void refreshMapNodes(JSONObject nodes) throws JSONException {
        Context context = this;

        //clearing overlay
        if (items != null) {
            for (OverlayItem m : items) {
                map.getOverlays().remove(m);
            }
            items.clear();
            itemsMap.clear();
        }

        //reading Overpass request answer
        JSONArray elements = ((JSONArray)nodes.get("elements"));
        for (int i = 0; i < elements.length(); i++) {
            JSONObject node = elements.getJSONObject(i);
            Log.i("node is:", node.toString());
            itemsMap.put(node.optString("id"), (JSONObject)node.get("tags"));
            items.add(new OverlayItem(((JSONObject)node.get("tags")).optString("name"), node.optString("id"), new GeoPoint(Double.parseDouble(node.optString("lat")), Double.parseDouble(node.optString("lon")))));
        }

        ItemizedOverlayWithFocus<OverlayItem> mOverlay = new ItemizedOverlayWithFocus<OverlayItem>(items,
                new ItemizedIconOverlay.OnItemGestureListener<OverlayItem>() {
                    @Override
                    public boolean onItemSingleTapUp(final int index, final OverlayItem item) {
                        String id = item.getSnippet();
                        JSONObject aboutJSON = (JSONObject) itemsMap.get(id);
                        String about = "";
                        Iterator<String> iter = aboutJSON.keys();
                        while (iter.hasNext()) {
                            String key = iter.next();
                            try {
                                about += key + ": " + aboutJSON.get(key) + "\n";
                            } catch (JSONException e) {
                                Log.e("show key:", "Something went wrong");
                            }
                        }
                        text.setText(about);
                        return true;
                    }
                    @Override
                    public boolean onItemLongPress(final int index, final OverlayItem item) {
                        return false;
                    }
                }, context);
        mOverlay.setFocusItemsOnTap(true);


        map.getOverlays().add(mOverlay);
    }

    public class MyLocationListener implements LocationListener {

        public void onLocationChanged(Location location) {
            currentLocation = new GeoPoint(location);
            displayMyCurrentLocationOverlay();
        }

        public void onProviderDisabled(String provider) {
        }

        public void onProviderEnabled(String provider) {
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    }

    private void displayMyCurrentLocationOverlay() {
        Log.i("GeoPoint location is", Double.toString(currentLocation.getLatitude()) + Double.toString(currentLocation.getLongitude()));
        if( currentLocation != null) {
            ArrayList<OverlayItem> thePoint = new ArrayList<OverlayItem>();
            thePoint.add(new OverlayItem("", "", currentLocation));
            currentLocationOverlay = new ItemizedOverlayWithFocus<OverlayItem>(thePoint, null, this);
            map.getOverlays().add(currentLocationOverlay);
            map.getController().setCenter(currentLocation);
        }
    }

    private void requestPermissionsIfNecessary(String[] permissions) {
        ArrayList<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }
        if (permissionsToRequest.size() > 0) {
            ActivityCompat.requestPermissions(
                    this,
                    permissionsToRequest.toArray(new String[0]),
                    REQUEST_PERMISSIONS_REQUEST_CODE);
        }
    }
}