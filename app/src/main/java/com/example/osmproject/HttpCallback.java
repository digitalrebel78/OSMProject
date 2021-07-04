package com.example.osmproject;

import org.json.JSONException;
import org.json.JSONObject;

public interface HttpCallback {
    public void refreshMapNodes(JSONObject jsonObject) throws JSONException;
}
